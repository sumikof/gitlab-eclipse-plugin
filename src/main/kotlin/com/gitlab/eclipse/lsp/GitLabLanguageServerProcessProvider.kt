package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.chat.utils.refreshDuoChatWindow
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import com.gitlab.eclipse.security.SecurityScanLifecycle
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.osgi.framework.Bundle
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
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
) {
  companion object {
    private const val LANGUAGE_SERVER_STARTED_TIMEOUT_SECONDS = 30L
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

  fun start(bundle: Bundle) {
    // Fire-and-forget: workbench startup must never block on the initialize handshake,
    // so the future returned by startLocked is deliberately ignored here.
    synchronized(lifecycleLock) { startLocked(bundle) }
  }

  fun stop(): Unit = synchronized(lifecycleLock) { stopLocked() }

  /**
   * Atomically restarts the language server: stop and start run under the lifecycle lock,
   * so concurrent start/stop/restart calls cannot interleave. Success is reported only
   * after the new server completes the initialize handshake (bounded by
   * [LANGUAGE_SERVER_STARTED_TIMEOUT_SECONDS]). On any failure — spawn error, rejected
   * initialize, or timeout — the provider settles in the stopped state (no partial start
   * is left behind) and returns false; calling restart again retries from that clean state.
   */
  fun restart(bundle: Bundle): Boolean = synchronized(lifecycleLock) {
    logger.info("Restarting the Language Server.")
    stopLocked()
    try {
      // Await the RAW initialize future (completed by the lsp4j listener thread), not the
      // handleAsync stage: the stage takes lifecycleLock, which this thread holds.
      val initialization = startLocked(bundle)
      val startedProcess = checkNotNull(process) { "Language server process is not tracked after start" }
      // Race the handshake against the process's own exit so a server that dies during
      // (or right after) initialization fails the restart quickly instead of timing out.
      CompletableFuture.anyOf(initialization, startedProcess.onExit())
        .get(LANGUAGE_SERVER_STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      check(initialization.isDone) { "Language server exited before completing initialization" }
      initialization.get()
      check(startedProcess.isAlive) { "Language server exited right after initialization" }
      true
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      logger.error("Failed to restart the Language Server.", e)
      stopLocked()
      false
    } catch (e: Exception) {
      // Any start failure must settle to the stopped state, not leave a partial start.
      logger.error("Failed to restart the Language Server.", e)
      stopLocked()
      false
    }
  }

  private fun startLocked(bundle: Bundle): CompletableFuture<InitializeResult> {
    val languageServerInstallationPath = languageServerInstaller.install()
      ?: error("Language server installation failed")

    val startedProcess = try {
      processFactory(createProcessBuilder(languageServerInstallationPath))
    } catch (e: IOException) {
      logger.error("Failed to create the Language Server process.", e)
      throw IllegalStateException("Language server process could not be created", e)
    }
    process = startedProcess
    // A new connection begins here, so the teardown below must be able to run for it. The epoch is
    // untouched: this connection runs at the one the previous stop advanced to.
    DiagnosticGenerationRegistry.onServerStarted()

    // This connection's published handle, once there is one. An AtomicReference rather than a
    // captured local because the callbacks below read it from other threads, and the initialize
    // callback reads it without holding lifecycleLock.
    val handleRef = AtomicReference<LanguageServerHandle?>(null)

    tearDownOnExit(startedProcess, handleRef)

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
    // on lifecycleLock, which the initialize callback does not hold.
    val handle = LanguageServerHandle(languageServerProxy.remoteProxy, client.session)
    handleRef.set(handle)
    languageServerWrapper.registerLanguageServer(handle)

    val initializeResult = languageServerProxy
      .remoteProxy
      .initialize(
        getInitializationOptions(bundle.version.toString())
      )
    initializeResult
      .handleAsync { result, err ->
        if (err != null) {
          logger.error("Failed to initialize Language Server", err)
          // The process can outlive a rejected handshake, so nothing else would take the
          // un-initialized proxy back. This branch holds no lock, so it is the one that can truly
          // run beside a restart: the conditional revocation is what stops a superseded server's
          // late failure from clearing the connection that replaced it.
          handleRef.get()?.let { languageServerWrapper.unregisterLanguageServer(it) }
        } else if (synchronized(lifecycleLock) { process !== startedProcess }) {
          // A superseded server's late init response must not run the readiness side
          // effects. They are bound to this callback's own proxy (below), so they could
          // no longer reach a newer server — but skipping them avoids pointless sends
          // to a process that is already dead.
          logger.info("Ignoring initialization result from a superseded Language Server process.")
        } else {
          logger.info("Initialized Language Server: $result")
          languageServerProxy.remoteProxy.initialized(null)
          // Pass the captured proxy explicitly: the sends launch coroutines, and a rapid
          // second restart can register the new server's proxy before they run. Binding
          // them here strands the superseded callback's queued work at the old server
          // instead of redirecting it at the new one before its initialize completes.
          val readinessServer = languageServerProxy.remoteProxy
          languageServerConfigurationService.sendConfiguration(readinessServer)
          languageServerOpenFilesService.sendOpenTabs(readinessServer)
          languageServerWebviewService.sendThemeChange(readinessServer)
          languageServerWebviewService.subscribeToThemeChanges()
          // The Duo Chat view shows a "not ready" page when opened before the LS is up;
          // re-evaluate it now that the LS is ready. asyncExec (not syncExec) so this LS
          // callback thread never blocks on the UI thread. refreshDuoChatWindow() is
          // null-guarded, so it is safe when the view is not open.
          currentDisplay.asyncExec { refreshDuoChatWindow() }
        }
      }.completeOnTimeout(Unit, LANGUAGE_SERVER_STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return initializeResult
  }

  /**
   * Arranges for [startedProcess]'s own exit to tear its connection down, using whatever handle
   * [handleRef] holds by then. Only the process that is still the tracked one may do so.
   */
  private fun tearDownOnExit(startedProcess: Process, handleRef: AtomicReference<LanguageServerHandle?>) {
    startedProcess.onExit().thenApply {
      synchronized(lifecycleLock) {
        // A late exit notification from a previous process must not clobber the
        // state of a newer process started by restart().
        if (process === startedProcess) {
          logger.info("Language Server exited.")
          process = null
          processListener = null
          // A crash or a self-inflicted exit never reaches stopLocked(), so the connection teardown
          // has to run here too — inside the identity guard, so a superseded process cannot tear
          // down the connection that replaced it. Both steps below are idempotent with stopLocked()
          // for one connection, and neither of them throws (the revocation is a compare-and-set),
          // so they cannot break this notification chain.
          handleRef.get()?.let { languageServerWrapper.unregisterLanguageServer(it) }
          SecurityScanLifecycle.onServerStopped()
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
    val logFile = getPluginStateDirectory().resolve("language_server.log")
    logger.info("Language server logs saved to: ${logFile.absolutePath}.")

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

  private fun getInitializationOptions(pluginVersion: String) = InitializeParams().apply {
    processId = process?.pid()?.toInt()
    capabilities = ClientCapabilities(
      WorkspaceClientCapabilities().also { capabilities ->
        capabilities.configuration = true
        capabilities.workspaceFolders = true
        capabilities.workspaceEdit = WorkspaceEditCapabilities().apply {
          documentChanges = true
          // We accept no create / rename / delete operations: the applier rejects them outright.
          resourceOperations = emptyList()
          failureHandling = "abort"
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
