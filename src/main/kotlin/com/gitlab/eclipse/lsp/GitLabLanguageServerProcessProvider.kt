package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageServer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI

class GitLabLanguageServerProcessProvider : KoinComponent, ProcessStreamConnectionProvider() {
  private val logger = logger<GitLabLanguageServerProcessProvider>()

  private val languageServerConfigurationService by inject<GitLabLanguageServerConfigurationService>()

  private val languageServerProxyManager by inject<LanguageServerProxyManager>()

  private val languageServerInstaller by inject<LanguageServerInstaller>()

  init {
    val languageServerInstallationPath = languageServerInstaller.install()

    if (languageServerInstallationPath != null) {
      commands = listOf(languageServerInstallationPath, "--stdio")
    } else {
      logger.error("Language server installation failed")
    }
  }

  override fun createProcessBuilder(): ProcessBuilder {
    val builder = super.createProcessBuilder()

    if (!BuildConfig.IS_EQUO_IDE) {
      val metadataDirectory = Platform.getLogFileLocation().toFile().parentFile
        ?: return builder

      val lsLogFile = metadataDirectory.resolve("language-server.log")

      if (!lsLogFile.exists()) {
        lsLogFile.createNewFile()
      }

      builder.redirectError(lsLogFile)
      logger.info("Language server logs saved to: ${lsLogFile.absolutePath}.")
    }

    builder.injectHttpProxyEnvironmentVariables()
    return builder
  }

  override fun start() {
    super.start()

    getAdapter(ProcessHandle::class.java)?.apply {
      logger.info("Language server exit bindings defined.")

      onExit().thenApply {
        logger.warn("Language server process exited.")
      }
    }
  }

  override fun handleMessage(message: Message, languageServer: LanguageServer, rootURI: URI?) {
    if (languageServer !is GitLabLanguageServer) {
      error("Language server should be a GitLabLanguageServer")
    }

    if (message is NotificationMessage) {
      when (message.method) {
        "initialized" -> {
          getKoin().setProperty(PROPERTY_LANGUAGE_SERVER, languageServer)
          languageServerConfigurationService.sendConfiguration()
        }

        else -> {}
      }
    }

    super.handleMessage(message, languageServer, rootURI)
  }

  override fun getInitializationOptions(rootUri: URI?): Any {
    return mapOf(
      "extension" to mapOf(
        "name" to "gitlab-eclipse-plugin",
        "version" to System.getProperty("eclipse.buildId")
      ),
      "ide" to mapOf(
        "name" to "gitlab-eclipse-plugin",
        "vendor" to "GitLab",
        "version" to System.getProperty("eclipse.buildId")
      ),
      "folders" to listOf(rootUri.toString())
    )
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
