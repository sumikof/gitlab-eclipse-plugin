package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.CodeCompletion
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.Telemetry
import com.gitlab.eclipse.preferences.PreferenceConstants.GITLAB_INSTANCE_URL
import com.gitlab.eclipse.preferences.PreferenceConstants.IGNORE_CERTIFICATE_ERRORS
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS
import com.gitlab.eclipse.preferences.PreferenceConstants.TELEMETRY_ENABLED
import com.gitlab.eclipse.preferences.storage.SecretStorage
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.FrameworkUtil
import java.net.URI

class GitLabLanguageServerProcessProvider(
  private val languageServerWrapper: GitLabLanguageServerWrapper = GitLabLanguageServerWrapper(),
  languageServerInstaller: LanguageServerInstaller = LanguageServerInstaller(),
) : ProcessStreamConnectionProvider() {
  private val logger by lazy {
    Platform.getLog(FrameworkUtil.getBundle(GitLabLanguageServerProcessProvider::class.java))
  }

  init {
    val languageServerInstallationPath = languageServerInstaller.install()

    if (languageServerInstallationPath != null) {
      commands = listOf(languageServerInstallationPath, "--stdio")
    } else {
      logger.error("Language server installation failed")
    }
  }

  override fun start() {
    super.start()

    getAdapter(ProcessHandle::class.java)?.apply {
      logger.warn("Language server exit bindings defined")

      onExit().thenApply {
        logger.warn("Language server process exited")
        logger.warn("LSP STDOUT: " + inputStream?.readAllBytes()?.decodeToString())
        logger.warn("LSP STDERR: " + errorStream?.readAllBytes()?.decodeToString())
      }
    }
  }

  override fun handleMessage(message: Message, languageServer: LanguageServer, rootURI: URI?) {
    if (message is NotificationMessage) {
      when (message.method) {
        "initialized" -> {
          onDidChangeConfiguration(languageServer)
          languageServerWrapper.registerLanguageServer(languageServer)
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

  // TODO: Extract into a service which registers the language server.
  private fun onDidChangeConfiguration(languageServer: LanguageServer) {
    val preferenceStore = ScopedPreferenceStore(
      InstanceScope.INSTANCE,
      FrameworkUtil.getBundle(GitLabLanguageServerProcessProvider::class.java).bundleId.toString()
    )

    val params = GitLabLanguageServerConfigurationParams.builder()
      .baseUrl(preferenceStore.getString(GITLAB_INSTANCE_URL))
      .codeCompletion(CodeCompletion(true, listOf(), listOf()))
      .featureFlags(
        GitLabLanguageServerConfigurationParams.FeatureFlags.builder()
          .remoteSecurityScans(false)
          .streamCodeGenerations(preferenceStore.getBoolean(LANGUAGE_SERVER_STREAM_CODE_GENERATIONS))
          .build()
      )
      .ignoreCertificateErrors(preferenceStore.getBoolean(IGNORE_CERTIFICATE_ERRORS))
      .logLevel(preferenceStore.getString(LANGUAGE_SERVER_LOG_LEVEL))
      .telemetry(
        Telemetry(
          preferenceStore.getBoolean(TELEMETRY_ENABLED),
          "https://snowplow.trx.gitlab.net"
        )
      )

    SecretStorage("gitlab.com")
      .getSecret("personal_access_token")
      ?.takeIf { it.isNotBlank() }
      ?.let { personalAccessToken -> params.token(personalAccessToken) }

    languageServer.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(params.build()))
  }
}
