package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.CodeCompletion
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.Telemetry
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.PreferenceInitializer
import com.gitlab.eclipse.preferences.storage.SecretStorage
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider
import org.eclipse.lsp4e.server.StreamConnectionProvider
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageServer
import java.net.URI

class GitLabLanguageServerProvider : ProcessStreamConnectionProvider(), StreamConnectionProvider {
    init {
        // TODO: Support configurable language server binary.
        commands = listOf(
            "/Users/erran/gitlab-org/editor-extensions/gitlab-lsp/bin/gitlab-lsp-macos-arm64",
            "--stdio"
        )
        // TODO: Make this workspace based.
        workingDirectory = "/Users/erran/eclipse-workspace/gitlab-eclipse-plugin/lsp-sandbox"
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

        super<ProcessStreamConnectionProvider>.handleMessage(message, languageServer, rootURI)
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

            val params = GitLabLanguageServerConfigurationParams.builder()
                .baseUrl(PreferenceInitializer.PREFERENCE_STORE.getString(PreferenceConstants.GITLAB_INSTANCE_URL))
                .codeCompletion(CodeCompletion(true, listOf(), listOf()))
                .featureFlags(
                    GitLabLanguageServerConfigurationParams.FeatureFlags.builder()
                        .remoteSecurityScans(false)
                        .streamCodeGenerations(PreferenceInitializer.PREFERENCE_STORE.getBoolean(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS))
                        .build()
                )
                .ignoreCertificateErrors(PreferenceInitializer.PREFERENCE_STORE.getBoolean(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS))
                .logLevel(PreferenceInitializer.PREFERENCE_STORE.getString(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL))
                .telemetry(
                    Telemetry(
                        PreferenceInitializer.PREFERENCE_STORE.getBoolean(PreferenceConstants.TELEMETRY_ENABLED),
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