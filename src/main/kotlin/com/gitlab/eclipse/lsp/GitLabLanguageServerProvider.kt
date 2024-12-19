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

class GitLabLanguageServerProvider : ProcessStreamConnectionProvider(
     listOf(
        "/Users/erran/gitlab-org/editor-extensions/gitlab-lsp/bin/gitlab-lsp-macos-arm64",
        "--stdio"
    ),
    "/Users/erran/eclipse-workspace/gitlab-eclipse-plugin/lsp-sandbox"
) {
    override fun start() {
        val server = InstallLanguageServer()
        server.install()

        // TODO: Ok, now start the dang thing!

        super.start()
    }

    override fun handleMessage(message: Message, languageServer: LanguageServer, rootURI: URI?) {
        if (Companion.languageServer == null && languageServer is GitLabLanguageServer) {
            Companion.languageServer = languageServer
        }

        if (message is NotificationMessage) {
            when (message.method) {
                "initialized" -> {
                    // TODO: Trigger through initialized rpc method?
                    onDidChangeConfiguration()
                    return
                }

                else -> {}
            }
        }

        super.handleMessage(message, languageServer, rootURI)
    }

    override fun getInitializationOptions(rootUri: URI?): Any? {
        return mapOf(
            "extension" to mapOf(
                "name" to "gitlab-eclipse-plugin",
                "version" to "0.1.0-erran"
            ),
            "ide" to mapOf(
                "name" to "gitlab-eclipse-plugin",
                "vendor" to "GitLab",
                "version" to "0.1.0-erran"
            ),
            "folders" to listOf(rootUri.toString())
        )
    }

    companion object {
        var languageServer: GitLabLanguageServer? = null

        // TODO: Extract into a service which registers the language server.
        fun onDidChangeConfiguration() {
            if (languageServer == null) {
                return
            }

            val preferenceStore = ScopedPreferenceStore(
                InstanceScope.INSTANCE,
                FrameworkUtil.getBundle(GitLabLanguageServerProvider::class.java).bundleId.toString()
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

            val secretStorage = SecretStorage("gitlab.com")
            secretStorage.getSecret("personal_access_token")?.let { personalAccessToken ->
                if (personalAccessToken.isNotBlank())
                params.token(personalAccessToken)
            }

            languageServer!!.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(params.build()))
        }
    }
}