package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.chat.utils.refreshDuoChatWindow
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
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

    startedProcess.onExit().thenApply {
      synchronized(lifecycleLock) {
        // A late exit notification from a previous process must not clobber the
        // state of a newer process started by restart().
        if (process === startedProcess) {
          logger.info("Language Server exited.")
          process = null
          processListener = null
        }
      }
    }

    if (!BuildConfig.IS_EQUO_IDE) {
      startedProcess.pullStdErrLogs()
    }

    val languageServerProxy = Launcher.Builder<GitLabLanguageServer>()
      .setLocalService(GitLabLanguageServerClient())
      .setRemoteInterface(GitLabLanguageServer::class.java)
      .setInput(startedProcess.inputStream)
      .setOutput(startedProcess.outputStream)
      .create()
      .also { processListener = it.startListening() }

    logger.info("Language server started successfully.")
    languageServerWrapper.registerLanguageServer(languageServerProxy.remoteProxy)

    val initializeResult = languageServerProxy
      .remoteProxy
      .initialize(
        getInitializationOptions(bundle.version.toString())
      )
    initializeResult
      .handleAsync { result, err ->
        if (err != null) {
          logger.error("Failed to initialize Language Server", err)
        } else if (synchronized(lifecycleLock) { process !== startedProcess }) {
          // A superseded server's late init response must not run the readiness side
          // effects: they would resolve against the wrapper's CURRENT proxy and fire at
          // the new server before its own initialize completes. A response that passes
          // this check can still race a restart that starts in the microseconds before
          // its side effects run — a pre-existing window this guard narrows, not closes.
          logger.info("Ignoring initialization result from a superseded Language Server process.")
        } else {
          logger.info("Initialized Language Server: $result")
          languageServerProxy.remoteProxy.initialized(null)
          languageServerConfigurationService.sendConfiguration()
          languageServerOpenFilesService.sendOpenTabs()
          languageServerWebviewService.sendThemeChange()
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

  private fun stopLocked() {
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
      },
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
    workspaceFolders = workspaceFolders
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
