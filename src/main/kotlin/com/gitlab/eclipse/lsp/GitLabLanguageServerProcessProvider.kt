package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.authentication.AuthenticationStateService
import com.gitlab.eclipse.chat.terminal.TerminalContextSourceProvider
import com.gitlab.eclipse.chat.utils.refreshDuoChatWindow
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.diagnostics.LanguageServerVersionState
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerFailureKind
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleStore
import com.gitlab.eclipse.lsp.lifecycle.StartOrigin
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import com.gitlab.eclipse.security.SecurityScanLifecycle
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformViews
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.osgi.framework.Bundle
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import com.gitlab.eclipse.lsp.utils.workspaceFolders as eclipseWorkspaceFolders

@Suppress("ForbiddenVoid")
class GitLabLanguageServerProcessProvider(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val languageServerConfigurationService: GitLabLanguageServerConfigurationService,
  private val languageServerOpenFilesService: GitLabLanguageServerOpenFilesService,
  private val languageServerProxyManager: LanguageServerProxyManager,
  private val languageServerWebviewService: LanguageServerWebviewService,
  private val languageServerInstaller: LanguageServerInstaller,
  private val processFactory: (ProcessBuilder) -> Process = { it.start() },
  /** Records every start attempt's outcome (LS-02, design §5.3); a Koin single in production. */
  private val lifecycleStore: LanguageServerLifecycleStore = LanguageServerLifecycleStore(),
  /**
   * Bounds the initialize handshake: the `orTimeout` on the initialize future and [restart]'s wait
   * share this one value (design §15 I8). Internal seam for the tests.
   */
  private val initializeTimeout: Duration = Duration.ofSeconds(DEFAULT_INITIALIZE_TIMEOUT_SECONDS),
) {
  companion object {
    private const val DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 30L
    private const val BUNDLE_SYMBOLIC_NAME = "com.gitlab.eclipse.gitlab-eclipse-plugin"

    /**
     * Gets the directory for GitLab plugin state files.
     * Uses Eclipse's state location API to ensure a consistent, writable location
     * regardless of how Eclipse was launched or the current working directory.
     */
    fun getPluginStateDirectory(): File {
      val bundle = Platform.getBundle(BUNDLE_SYMBOLIC_NAME)
        ?: error("Unable to find bundle $BUNDLE_SYMBOLIC_NAME")

      return Platform.getStateLocation(bundle).toFile()
    }
  }

  private val logger by lazy { logger<GitLabLanguageServerProcessProvider>() }
  private val languageServerLogger = LoggerFactory.getLogger("com.gitlab.eclipse.lsp")

  /** Serializes start/stop/restart and guards all lifecycle state below. */
  private val lifecycleLock = Any()

  private var process: Process? = null
  private var processListener: Future<Void>? = null
  private var pullStdErrLogsExecutor: ExecutorService? = null

  internal val isRunning: Boolean
    get() = synchronized(lifecycleLock) { process != null }

  /**
   * Starts the language server. Never throws: bundle activation calls this without a catch of its
   * own, so a failure here (no install for this platform, a failed extraction, a spawn error) would
   * skip the rest of activation. The failure is logged, the provider stays stopped, and a later
   * [restart] retries from that clean state.
   */
  fun start(bundle: Bundle) {
    // Fire-and-forget: workbench startup must never block on the initialize handshake,
    // so the future returned by startLocked is deliberately ignored here.
    synchronized(lifecycleLock) {
      try {
        startLocked(bundle, StartOrigin.STARTUP)
      } catch (_: LanguageServerStartAbortedException) {
        // The lifecycle store is closed: the bundle is stopping, so nothing is started or reported.
      } catch (_: LanguageServerNotInstalledException) {
        // startLocked threw before it could hand its generation back; every lifecycle mutation runs
        // under this lock, so the snapshot read here is that attempt's generation (design §15 I3).
        lifecycleStore.markFailed(lifecycleStore.snapshot().generation, LanguageServerFailureKind.INSTALL_FAILED)
        // The installer has already logged the reason as one clear error (for example "not bundled
        // for linux/aarch64"); a second error with a stack trace would only bury it.
        logger.info("The Language Server stays stopped: it could not be installed.")
      } catch (e: Exception) {
        // Generation read as above. Every remaining failure precedes the initialize handshake.
        lifecycleStore.markFailed(lifecycleStore.snapshot().generation, LanguageServerFailureKind.START_FAILED)
        // Class name only: the message of a spawn failure can carry the installation path.
        logger.error("Failed to start the Language Server (${rootCauseName(e)}).")
        // Only a start that got as far as a process has anything to undo.
        if (process != null) stopLocked()
      }
    }
  }

  fun stop(): Unit = synchronized(lifecycleLock) {
    val generation = lifecycleStore.snapshot().generation
    try {
      stopLocked()
    } finally {
      // An explicit stop always wins over the pending callbacks of this generation: they re-check
      // the process identity under this lock and find it gone.
      lifecycleStore.markStopped(generation)
    }
  }

  /**
   * Atomically restarts the language server: stop and start run under the lifecycle lock,
   * so concurrent start/stop/restart calls cannot interleave. Success is reported only
   * after the new server completes the initialize handshake (bounded by
   * [initializeTimeout]). On any failure — spawn error, rejected
   * initialize, or timeout — the provider settles in the stopped state (no partial start
   * is left behind) and returns false; calling restart again retries from that clean state.
   */
  fun restart(bundle: Bundle): Boolean {
    synchronized(lifecycleLock) {
      logger.info("Restarting the Language Server.")
      val previousGeneration = lifecycleStore.snapshot().generation
      stopLocked()
      // Not a failure edge, so no popup; it does close a failure popup of the previous generation.
      lifecycleStore.markStopped(previousGeneration)
      val failure = try {
        // Await the RAW initialize future (completed by the lsp4j listener thread), not the
        // handleAsync stage: the stage takes lifecycleLock, which this thread holds.
        val initialization = startLocked(bundle, StartOrigin.RESTART)
        val startedProcess = checkNotNull(process) { "Language server process is not tracked after start" }
        // Race the handshake against the process's own exit so a server that dies during
        // (or right after) initialization fails the restart quickly instead of timing out.
        CompletableFuture.anyOf(initialization, startedProcess.onExit())
          .get(initializeTimeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!initialization.isDone) throw LanguageServerExitedDuringStartupException()
        initialization.get()
        if (!startedProcess.isAlive) throw LanguageServerExitedDuringStartupException()
        // The initialize callback makes the same (then rejected) call once restart() releases the
        // lock; recording it here means a restart that reported success is already RUNNING.
        lifecycleStore.markRunning(lifecycleStore.snapshot().generation)
        return true
      } catch (_: LanguageServerStartAbortedException) {
        // The lifecycle store is closed: the bundle is stopping. Nothing was started.
        return false
      } catch (e: Exception) {
        e
      }
      // Restores the flag the interrupted wait consumed; the attempt still settles below (R2-I2).
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      // Recorded before the stop, under the lock that the attempt's own callbacks wait for, so they
      // find the generation already FAILED (first failure wins) and the process identity gone.
      val kind = failureKindOf(failure)
      lifecycleStore.markFailed(lifecycleStore.snapshot().generation, kind)
      logger.error("Failed to restart the Language Server ($kind, ${rootCauseName(failure)}).")
      // Any start failure must settle to the stopped state, not leave a partial start.
      stopLocked()
      return false
    }
  }

  private fun startLocked(bundle: Bundle, origin: StartOrigin): CompletableFuture<InitializeResult> {
    val generation = requireAttemptBegun(lifecycleStore.beginAttempt(origin))

    val languageServerInstallationPath = languageServerInstaller.install()
      ?: throw LanguageServerNotInstalledException()

    val startedProcess = try {
      processFactory(createProcessBuilder(languageServerInstallationPath))
    } catch (e: Exception) {
      // Logged by start()/restart(), by class name only: the message can carry the installation path.
      throw LanguageServerSpawnException(e)
    }
    process = startedProcess
    // A new connection begins here, so the teardown below must be able to run for it. The epoch is
    // untouched: this connection runs at the one the previous stop advanced to.
    DiagnosticGenerationRegistry.onServerStarted()

    // This connection's published handle, once there is one. An AtomicReference rather than a
    // captured local because the callbacks below read it from other threads, and the initialize
    // callback reads it without holding lifecycleLock.
    val handleRef = AtomicReference<LanguageServerHandle?>(null)

    tearDownOnExit(startedProcess, handleRef, generation)

    if (!BuildConfig.IS_EQUO_IDE) {
      startedProcess.pullStdErrLogs()
    }

    val client = GitLabLanguageServerClient()
    val languageServerProxy = Launcher.Builder<GitLabLanguageServer>()
      .setLocalService(client)
      .setRemoteInterface(GitLabLanguageServer::class.java)
      .setInput(startedProcess.inputStream)
      .setOutput(startedProcess.outputStream)
      .create()
      .also { processListener = it.startListening() }

    logger.info("Language server started successfully.")
    // Built once and handed to both, so the revocations below compare the very handle that was
    // published — compareAndSet is by reference, and an equal copy would never match. Stored in
    // handleRef first, so "the snapshot is published" implies "handleRef is set" without relying
    // on lifecycleLock, which the initialize callback does not hold. The epoch is the client's own
    // value, copied rather than read from the registry again, so what a sender stamps on a request
    // is exactly what this client compares the response against.
    val handle = LanguageServerHandle(languageServerProxy.remoteProxy, client.session, client.connectionEpoch)
    handleRef.set(handle)
    languageServerWrapper.registerLanguageServer(handle)

    // orTimeout completes the raw future itself, so restart()'s wait and the callback below both see
    // the same TimeoutException (design §5.3, §15 I8).
    val initializeResult = languageServerProxy
      .remoteProxy
      .initialize(
        initializationOptions(bundle.version.toString(), startedProcess.pid().toInt())
      )
      .orTimeout(initializeTimeout.toMillis(), TimeUnit.MILLISECONDS)
    initializeResult
      .handleAsync { result, err ->
        if (err != null) {
          onInitializeFailed(err, generation, startedProcess, handleRef)
        } else if (synchronized(lifecycleLock) {
            // The identity check and the RUNNING transition in one critical section (design §15
            // I3), so an explicit stop or restart cannot slip in between them.
            (process === startedProcess).also { current -> if (current) lifecycleStore.markRunning(generation) }
          }.not()
        ) {
          // A superseded server's late init response must not run the readiness side
          // effects. They are bound to this callback's own proxy (below), so they could
          // no longer reach a newer server — but skipping them avoids pointless sends
          // to a process that is already dead.
          logger.info("Ignoring initialization result from a superseded Language Server process.")
        } else {
          // No payload: the server version is recorded by LanguageServerVersionState below.
          logger.info("Initialized Language Server.")
          // The bundled version is pinned in package.json, which is a build input and not readable
          // at runtime; the server's own answer is what the diagnostics report quotes.
          LanguageServerVersionState.record(result?.serverInfo?.version)
          languageServerProxy.remoteProxy.initialized(null)
          // Pass the captured proxy explicitly: the sends launch coroutines, and a rapid
          // second restart can register the new server's proxy before they run. Binding
          // them here strands the superseded callback's queued work at the old server
          // instead of redirecting it at the new one before its initialize completes.
          // The configuration send takes the whole handle: it needs this connection's epoch
          // too, and it has to come from the same value as the proxy.
          val readinessServer = languageServerProxy.remoteProxy
          languageServerConfigurationService.sendConfiguration(handle)
          languageServerOpenFilesService.sendOpenTabs(readinessServer)
          languageServerWebviewService.sendThemeChange(readinessServer)
          languageServerWebviewService.subscribeToThemeChanges()
          // asyncExec (not syncExec) so this LS callback thread never blocks on the UI thread.
          currentDisplay.asyncExec { refreshWebviewsAfterReady(languageServerWrapper) }
        }
      }
    return initializeResult
  }

  /**
   * A rejected or timed-out initialize. The store call is unconditional with this attempt's own
   * [generation] (a stale one is rejected by the store); only the side effects are gated on the
   * process identity (design §5.3, F1/F2): the still-tracked process is stopped, while a superseded
   * one only takes back its own handle, so its late failure never kills the server that replaced it.
   */
  private fun onInitializeFailed(
    err: Throwable,
    generation: Long,
    startedProcess: Process,
    handleRef: AtomicReference<LanguageServerHandle?>,
  ) {
    val cause = unwrapCompletion(err)
    val kind = if (cause is TimeoutException) {
      LanguageServerFailureKind.INITIALIZE_TIMEOUT
    } else {
      LanguageServerFailureKind.INITIALIZE_FAILED
    }
    synchronized(lifecycleLock) {
      lifecycleStore.markFailed(generation, kind)
      if (process === startedProcess) {
        // Class name only: the error text is the LS's own message (design §10).
        logger.error("Failed to initialize the Language Server ($kind, ${cause.javaClass.simpleName}).")
        // Contained: this runs on a pool thread whose stage nobody observes, and the cleanup in
        // stopLocked()'s `finally` has already run by the time anything above it throws.
        runCatching { stopLocked() }.onFailure {
          logger.warn("Failed to stop the Language Server after a failed initialize (${it.javaClass.simpleName}).")
        }
      } else {
        // The process can outlive a rejected handshake, so nothing else would take the
        // un-initialized proxy back; the conditional revocation leaves a successor's connection alone.
        handleRef.get()?.let { languageServerWrapper.unregisterLanguageServer(it) }
        logger.info(
          "Ignoring initialization failure from a superseded Language Server process (${cause.javaClass.simpleName}).",
        )
      }
    }
  }

  /**
   * Arranges for [startedProcess]'s own exit to tear its connection down, using whatever handle
   * [handleRef] holds by then. Only the process that is still the tracked one may do so.
   */
  private fun tearDownOnExit(
    startedProcess: Process,
    handleRef: AtomicReference<LanguageServerHandle?>,
    generation: Long,
  ) {
    startedProcess.onExit().thenApply {
      synchronized(lifecycleLock) {
        // Unconditional with this process's own generation: the store ignores a stale one, an
        // already FAILED one and a STOPPED one (explicit stop), and picks EXITED_DURING_STARTUP or
        // CRASHED from the phase.
        lifecycleStore.exitObserved(generation)
        // A late exit notification from a previous process must not clobber the
        // state of a newer process started by restart().
        if (process === startedProcess) {
          val exitCode = runCatching { startedProcess.exitValue() }.getOrDefault(-1)
          logger.warn("Language Server exited unexpectedly (exit code $exitCode).")
          process = null
          processListener = null
          pullStdErrLogsExecutor?.shutdownNow()
          pullStdErrLogsExecutor = null
          // A crash or a self-inflicted exit never reaches stopLocked(), so the connection teardown
          // has to run here too — inside the identity guard, so a superseded process cannot tear
          // down the connection that replaced it. Both steps below are idempotent with stopLocked()
          // for one connection, and neither of them throws (the revocation is a compare-and-set),
          // so they cannot break this notification chain.
          handleRef.get()?.let { languageServerWrapper.unregisterLanguageServer(it) }
          SecurityScanLifecycle.onServerStopped()
          resetAuthenticationState(logger)
          resetCodeSuggestionsState(logger)
          resetTerminalContextState(logger)
        }
      }
    }
  }

  private fun stopLocked() {
    try {
      // Unregister language server before killing the process.
      languageServerWrapper.unregisterLanguageServer()

      // Unregister capabilities before killing the process.
      service<DidChangeWatchedFileCapability>().unregisterAll()

      processListener?.cancel(true)
      processListener = null

      pullStdErrLogsExecutor?.shutdownNow()
      pullStdErrLogsExecutor = null

      process?.destroy()
      process = null
    } finally {
      // The connection is gone: advance the epoch, cancel the commands that were waiting on it and
      // remove the markers it left behind. Runs last, when nothing of the connection is left, and
      // never throws, so restart()'s "settle in the stopped state" contract is unaffected.
      //
      // In a `finally` because the steps above can raise on a late or degraded stop — the
      // capability lookup goes through Koin, whose scope may already be closed — and this clean up
      // must not be the thing that a failure up there silently discards. It is also the last chance
      // the bundle's stop() has: everything after this call would be skipped by the same throw.
      //
      // Called from here as well as from onExit(): an explicit stop clears `process` under this same
      // lock, so the exit notification that follows fails its identity guard and never runs.
      SecurityScanLifecycle.onServerStopped()
      resetAuthenticationState(logger)
      resetCodeSuggestionsState(logger)
      resetTerminalContextState(logger)
    }
  }

  private fun createProcessBuilder(path: String): ProcessBuilder {
    val builder = ProcessBuilder(path, "--stdio")

    if (BuildConfig.IS_EQUO_IDE) {
      builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    }

    builder.injectHttpProxyEnvironmentVariables()
    return builder
  }

  private fun Process.pullStdErrLogs() {
    // No path in the Error Log (AC-G1); the stderr lines go to the LS's own log file via languageServerLogger.
    logger.info("Language server logs saved to language_server.log in the plugin state directory.")

    pullStdErrLogsExecutor = Executors.newSingleThreadExecutor().apply {
      execute {
        while (isAlive) {
          val stderrLine = errorReader().readLine()
          if (stderrLine != null) {
            languageServerLogger.info(stderrLine)
          }
        }
      }
    }
  }

  private fun ProcessBuilder.injectHttpProxyEnvironmentVariables() {
    val proxySettings = mapOf(
      "http_proxy" to languageServerProxyManager.getHttpProxyUrl(),
      "HTTPS_PROXY" to languageServerProxyManager.getHttpsProxyUrl(),
      "NO_PROXY" to languageServerProxyManager.getBypassHosts()
    )

    proxySettings.forEach { (key, value) ->
      if (value != null) {
        logger.info("Passing through $key to the language server.")
        environment()[key] = value
      }
    }
  }
}

/**
 * The connection is gone, so nothing is known about the next one's authentication state: the
 * "Sign in" menu item goes back to undetermined until the new connection's state settles.
 *
 * Contained, because it sits on the two "never throws" paths of the provider (the `finally` of the
 * stop path and the exit notification chain): the service resolves the workbench's source provider,
 * which is not there any more while the workbench itself is shutting down. Only the exception's
 * class name is logged.
 */
private fun resetAuthenticationState(logger: ILog) {
  runCatching { service<AuthenticationStateService>().resetForConnectionChange() }
    .onFailure { logger.warn("Skipped the authentication state reset: ${it.javaClass.simpleName}") }
}

/**
 * The connection is gone, so its Code Suggestions feature state is unknown again: the status shows
 * "Checking..." until the next connection reports (design §8.3). Contained for the same reasons as
 * [resetAuthenticationState].
 */
private fun resetCodeSuggestionsState(logger: ILog) {
  runCatching { service<CodeSuggestionsStateService>().reset() }
    .onFailure { logger.warn("Skipped the code suggestions state reset: ${it.javaClass.simpleName}") }
}

/**
 * Hides "Explain Terminal Output with Duo" as soon as the connection is gone, instead of when a new
 * connection reports. Contained for the same reasons as [resetAuthenticationState].
 */
private fun resetTerminalContextState(logger: ILog) {
  runCatching { service<TerminalContextSourceProvider>().reset() }
    .onFailure { logger.warn("Skipped the terminal context state reset: ${it.javaClass.simpleName}") }
}

/** [LanguageServerInstaller.install] returned null; the installer has logged why. */
internal class LanguageServerNotInstalledException : IllegalStateException("Language server installation failed")

/** The process factory threw (spawn failure); [cause] is the original exception. */
internal class LanguageServerSpawnException(cause: Throwable) :
  IllegalStateException("Language server process could not be created", cause)

/** The new process exited before (or right after) completing the initialize handshake. */
internal class LanguageServerExitedDuringStartupException :
  IllegalStateException("Language server exited during startup")

/** The lifecycle store is closed (the bundle is stopping): no attempt was begun. */
private class LanguageServerStartAbortedException : IllegalStateException("Language server start aborted")

/** [generation] as returned by `beginAttempt`; -1 means the store is closed and nothing may start. */
private fun requireAttemptBegun(generation: Long): Long {
  if (generation < 0) throw LanguageServerStartAbortedException()
  return generation
}

/**
 * Maps a failure of `startLocked` / `restart()`'s wait to its kind by exception TYPE, never by
 * message (design §15 R2-I1). A timeout can arrive bare (restart's own wait) or wrapped in an
 * [ExecutionException] (the `orTimeout` of the initialize future won the race, R2-I4).
 */
internal fun failureKindOf(e: Throwable): LanguageServerFailureKind = when (e) {
  is LanguageServerNotInstalledException -> LanguageServerFailureKind.INSTALL_FAILED
  is LanguageServerExitedDuringStartupException -> LanguageServerFailureKind.EXITED_DURING_STARTUP
  is TimeoutException -> LanguageServerFailureKind.INITIALIZE_TIMEOUT
  is ExecutionException, is CompletionException ->
    if (unwrapCompletion(e) is TimeoutException) {
      LanguageServerFailureKind.INITIALIZE_TIMEOUT
    } else {
      LanguageServerFailureKind.INITIALIZE_FAILED
    }
  // Spawn failures, an interrupted wait (R2-I2) and anything else before the handshake.
  else -> LanguageServerFailureKind.START_FAILED
}

private tailrec fun unwrapCompletion(e: Throwable): Throwable {
  val cause = e.cause
  return if ((e is ExecutionException || e is CompletionException) && cause != null) unwrapCompletion(cause) else e
}

/** The simple class name worth logging: the spawn / completion wrappers are ours, their cause is not. */
private fun rootCauseName(e: Throwable): String {
  val unwrapped = unwrapCompletion(e)
  val reported = if (unwrapped is LanguageServerSpawnException) unwrapped.cause ?: unwrapped else unwrapped
  return reported.javaClass.simpleName
}

/**
 * The readiness UI turn of [GitLabLanguageServerProcessProvider] (top-level to keep the class's
 * function count). The Duo Chat view shows a "not ready" page when opened before the LS is up;
 * re-evaluate it now. refreshDuoChatWindow() is null-guarded, so it is safe when the view is
 * not open. The Agent Platform views re-resolve their pages in the same turn (design AGENT-02
 * §5.2). The snapshot is read here, inside the runnable, not in the callback: if yet another
 * server took over before this runs, the views are compared with that one and never sent back to
 * a superseded one. `finally`, so a Duo Chat refresh that throws (a workbench that is closing)
 * neither keeps the views stale nor changes what the event loop sees from it.
 */
private fun refreshWebviewsAfterReady(languageServerWrapper: GitLabLanguageServerWrapper) {
  try {
    refreshDuoChatWindow()
  } finally {
    AgentPlatformViews.reloadStale(languageServerWrapper.currentSnapshot?.session)
  }
}

/**
 * The initialize request's parameters (top-level to keep the provider's function count). [pid] is
 * named apart from the receiver's own `processId`, which a same-named parameter would shadow into a
 * silent self-assignment.
 */
private fun initializationOptions(pluginVersion: String, pid: Int?) = InitializeParams().apply {
  processId = pid
  capabilities = ClientCapabilities(
    WorkspaceClientCapabilities().also { capabilities ->
      capabilities.configuration = true
      capabilities.workspaceFolders = true
      capabilities.workspaceEdit = WorkspaceEditCapabilities().apply {
        documentChanges = true
        // We accept no create / rename / delete operations: the applier rejects them outright.
        resourceOperations = emptyList()
        failureHandling = FailureHandlingKind.Abort
      }
    },
    TextDocumentClientCapabilities().apply {
      completion = CompletionCapabilities().apply {
        completionItem = CompletionItemCapabilities()
        completionItemKind = CompletionItemKindCapabilities(listOf(CompletionItemKind.Text))
        contextSupport = true
        insertTextMode = InsertTextMode.AdjustIndentation
      }
    },
    WindowClientCapabilities().apply {
      showMessage = WindowShowMessageRequestCapabilities().apply {
        messageActionItem = WindowShowMessageRequestActionItemCapabilities()
      }
      showDocument = ShowDocumentCapabilities(true)
    },
    // Explicit trailing null: lsp4j 1.0.0 has no 3-arg (Workspace, TextDocument, Window)
    // constructor, only (Workspace, TextDocument, Object experimental) and this 4-arg one. Without
    // this argument, Kotlin silently picks the 3-arg overload and boxes the whole
    // WindowClientCapabilities into `experimental`, so `window` (and therefore both showMessage
    // and showDocument) never reaches the wire.
    null,
  )
  clientInfo = ClientInfo(
    "gitlab-eclipse-plugin",
    System.getProperty("eclipse.buildId")
  )
  initializationOptions = mapOf(
    "extension" to mapOf(
      "name" to "GitLab Duo",
      "version" to pluginVersion
    ),
    "ide" to mapOf(
      "name" to "Eclipse",
      "vendor" to "Eclipse",
      "version" to Platform.getBundle("org.eclipse.platform").version.toString()
    ),
  )
  // Aliased import: inside this `apply`, the receiver's own `workspaceFolders` member shadows a
  // plain import of the top-level one, which turns this line into a silent self-assignment.
  workspaceFolders = eclipseWorkspaceFolders
}
