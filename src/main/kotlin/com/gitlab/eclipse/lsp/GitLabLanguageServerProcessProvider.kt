package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.IOException
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
) {
  companion object {
    private const val LANGUAGE_SERVER_STARTED_TIMEOUT_SECONDS = 30L
  }

  private val logger = logger<GitLabLanguageServerProcessProvider>()
  private var process: Process? = null
  private var processListener: Future<Void>? = null

  fun start() {
    val languageServerInstallationPath = languageServerInstaller.install()
      ?: error("Language server installation failed")

    process = try {
      createProcessBuilder(languageServerInstallationPath).start()
    } catch (e: IOException) {
      logger.error("Failed to the Language Server create process.", e)
      null
    }

    process?.onExit()?.thenApply {
      logger.info("Language Server exited.")
      process = null
    }

    val languageServerProxy = Launcher.Builder<GitLabLanguageServer>()
      .setLocalService(GitLabLanguageServerClient())
      .setRemoteInterface(GitLabLanguageServer::class.java)
      .setInput(process?.inputStream)
      .setOutput(process?.outputStream)
      .create()
      .also { processListener = it.startListening() }

    logger.info("Language server started successfully.")
    languageServerWrapper.registerLanguageServer(languageServerProxy.remoteProxy)

    languageServerProxy.remoteProxy.initialize(getInitializationOptions()).handleAsync { result, err ->
      if (err != null) {
        logger.error("Failed to initialize Language Server", err)
      } else {
        logger.info("Initialized Language Server: $result")
        languageServerProxy.remoteProxy.initialized(null)
        languageServerConfigurationService.sendConfiguration()
        languageServerOpenFilesService.sendOpenTabs()
        languageServerWebviewService.sendThemeChange()
        languageServerWebviewService.subscribeToThemeChanges()
      }
    }.completeOnTimeout(Unit, LANGUAGE_SERVER_STARTED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  fun stop() {
    // Unregister language server before killing the process.
    languageServerWrapper.unregisterLanguageServer()

    // Unregister capabilities before killing the process.
    service<DidChangeWatchedFileCapability>().unregisterAll()

    processListener?.cancel(true)

    process?.destroy()
    process = null
  }

  private fun createProcessBuilder(path: String): ProcessBuilder {
    val builder = ProcessBuilder(path, "--stdio")

    if (!BuildConfig.IS_EQUO_IDE) {
      val metadataDirectory = Platform.getLogFileLocation().toFile().parentFile
        ?: return builder

      val lsLogFile = metadataDirectory.resolve("language-server.log")

      if (!lsLogFile.exists()) {
        lsLogFile.createNewFile()
      }

      builder.redirectError(ProcessBuilder.Redirect.appendTo(lsLogFile))
      logger.info("Language server logs saved to: ${lsLogFile.absolutePath}.")
    } else {
      builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    }

    builder.injectHttpProxyEnvironmentVariables()
    return builder
  }

  private fun getInitializationOptions() = InitializeParams().apply {
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
        "name" to "gitlab-eclipse-plugin",
        "version" to System.getProperty("eclipse.buildId")
      ),
      "ide" to mapOf(
        "name" to "gitlab-eclipse-plugin",
        "vendor" to "GitLab",
        "version" to System.getProperty("eclipse.buildId")
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
