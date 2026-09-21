package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.authentication.OAuthSecretStorage
import com.gitlab.eclipse.authentication.PatTokenProvider
import com.gitlab.eclipse.inject.service

/**
 * Reads the credentials this installation holds **from local storage only** (design §10.1).
 *
 * This exists because [com.gitlab.eclipse.authentication.GitLabTokenProviderManager.getToken] is
 * not safe to call from anything diagnostics do. On the OAuth path it runs
 * `OAuthTokenProvider.refreshTokenIfExpired()`, which:
 *
 * - performs a **blocking HTTP round trip** (`GitLabOAuthService.refreshToken`), and
 * - on failure shows a notification **and flips `AUTHENTICATION_TYPE` to PAT**
 *   (`OAuthTokenProvider.kt:79-80`).
 *
 * Both are wrong here, and the second is worse than the first: a report generated against an
 * unreachable instance would *change the authentication type it is reporting on*. Diagnostics must
 * not alter the state they describe. The first also freezes the workbench, since
 * `ShowDiagnosticsHandler` runs on the UI thread — exactly when the user is most likely offline and
 * reaching for the diagnostics command.
 *
 * Both reads below go to Eclipse secure storage and stop there. They do log (via
 * `OAuthSecretStorage`), so this must never be called from the log-capture path — [DiagnosticsLog]
 * reads the published cache instead.
 */
internal object StoredSecrets {

  /**
   * Every credential value currently in storage. Empty when none is configured, or when the lookup
   * fails — a diagnostics run must not die because secure storage is unavailable.
   */
  fun current(): List<String> = (listOf(pat()) + oauth()).filterNot { it.isNullOrBlank() }.filterNotNull()

  /** Whether any credential is configured, for the report's `Token configured` line. */
  fun anyConfigured(): Boolean = current().isNotEmpty()

  /**
   * Publishes what is in storage into [DiagnosticsSecrets], so the literal redaction rule has
   * something to match even when the language server never started and therefore never built a
   * configuration payload.
   */
  fun publish() {
    DiagnosticsSecrets.publishAll(current())
  }

  private fun pat(): String? = runCatching { service<PatTokenProvider>().getToken() }.getOrNull()

  /**
   * The OAuth **access and refresh** tokens as stored. The refresh token is included deliberately:
   * it is a credential in its own right, and the configuration payload never carries it, so
   * publishing from the configuration build alone would leave it uncovered.
   */
  private fun oauth(): List<String?> = runCatching {
    val stored = OAuthSecretStorage().getOAuthToken()
    listOf(stored?.accessToken, stored?.refreshToken)
  }.getOrDefault(emptyList())
}
