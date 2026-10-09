package com.gitlab.eclipse.authentication

/**
 * The classified result of a single OAuth token refresh attempt (design §7.1 / §9).
 *
 * [Rejected.error] and [Transient.reason] are non-sensitive labels for logging only — an OAuth
 * error code or an exception type name — never a token or a response body.
 */
sealed interface RefreshOutcome {
  /** The server answered with a new, usable token. */
  data class Refreshed(val token: GitLabAuthorizationToken) : RefreshOutcome

  /** The server rejected the refresh token itself (e.g. invalid_grant): it is unusable. */
  data class Rejected(val error: String) : RefreshOutcome

  /** No usable answer: timeout, I/O failure, non-OAuth error status, or an unparsable body. */
  data class Transient(val reason: String) : RefreshOutcome
}
