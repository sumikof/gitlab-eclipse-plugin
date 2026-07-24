package com.gitlab.eclipse.preferences

/**
 * Constant definitions for plug-in preferences
 */
object PreferenceConstants {
  const val GITLAB_INSTANCE_URL: String = "gitlab.url"
  const val LANGUAGE_SERVER_LOG_LEVEL: String = "gitlab.languageServer.logLevel"
  const val LANGUAGE_SERVER_HTTP_URL: String = "gitlab.languageServer.httpUrl"
  const val LANGUAGE_SERVER_STREAM_CODE_GENERATIONS: String = "gitlab.languageServer.streamCodeGenerations"
  const val TELEMETRY_ENABLED: String = "gitlab.telemetry.enabled"
  const val IGNORE_CERTIFICATE_ERRORS: String = "gitlab.certificate.ignoreCertificate"
  const val CA_CERTIFICATE: String = "gitlab.certificate.caCertificate"
  const val AUTHENTICATION_TYPE: String = "gitlab.authentication.type"
  const val CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES: String = "gitlab.codeSuggestions.additionalLanguages"
  const val CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES: String = "gitlab.codeSuggestions.disabledSupportedLanguages"
  const val CLIENT_CERTIFICATE: String = "gitlab.certificate.clientCertificate"
  const val CLIENT_CERTIFICATE_KEY: String = "gitlab.certificate.clientCertificateKey"
  const val CODE_SUGGESTIONS_ENABLED: String = "gitlab.codeSuggestions.enabled"
  const val DUO_CHAT_ENABLED: String = "gitlab.duoChat.enabled"
  const val DUO_ENABLED_WITHOUT_GITLAB_PROJECT: String = "gitlab.duo.enabledWithoutGitlabProject"
  const val DUO_CHAT_SELECTED_WEBVIEW: String = "gitlab.duoChat.selectedWebview"
}
