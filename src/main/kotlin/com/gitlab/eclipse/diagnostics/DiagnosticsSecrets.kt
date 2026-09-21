package com.gitlab.eclipse.diagnostics

/**
 * The credential values currently in use, cached so that redacting a log line costs nothing
 * (design §10.1).
 *
 * **This cache exists because the obvious implementation is unusable.** Asking
 * `GitLabTokenProviderManager.getToken()` for the live token on the OAuth path runs
 * `OAuthTokenProvider.refreshTokenIfExpired()`, which performs a network round trip, writes three
 * log lines and can raise a notification (`OAuthTokenProvider.kt:69-87`). [PluginLogTap] runs
 * inside Eclipse's log delivery on every log line, so calling that from there would block log
 * delivery on the network, re-enter the tap through its own logging, and pop a dialog from inside a
 * log call. None of that is acceptable on a path whose entire job is to be cheap and silent.
 *
 * So the value is *published* from somewhere that already holds it and already runs off the logging
 * path, and read here as a plain volatile field.
 *
 * **Consequence, deliberate and documented:** until something publishes a token, literal redaction
 * has nothing to match and only the shape-based patterns in [DiagnosticsSanitizer] apply. That
 * degrades the guarantee for an installation whose language server has never started — which is
 * also an installation that has made no authenticated requests to log a token from.
 */
object DiagnosticsSecrets {

  @Volatile
  private var values: Set<String> = emptySet()

  /**
   * Records [candidates] as values to redact. Blank entries are ignored.
   *
   * Callers pass what they already have; this never fetches anything. Values accumulate rather than
   * replace, so a token that has since been rotated is still redacted out of the lines captured
   * while it was current — the leak Codex's second review round identified. The set is small (one
   * PAT and/or one OAuth access token per rotation), so unbounded growth is not a practical concern
   * within a workbench session, and [clear] exists for tests.
   */
  fun publish(vararg candidates: String?) {
    val fresh = candidates.filterNot { it.isNullOrBlank() }.filterNotNull()
    if (fresh.isEmpty()) return
    synchronized(this) { values = values + fresh }
  }

  /** The values to redact. Never blocks, never logs, never touches the network. */
  fun current(): Collection<String> = values

  /** Test seam. */
  internal fun clear() {
    synchronized(this) { values = emptySet() }
  }
}
