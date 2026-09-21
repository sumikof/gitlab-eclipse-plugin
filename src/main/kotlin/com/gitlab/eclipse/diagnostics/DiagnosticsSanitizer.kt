package com.gitlab.eclipse.diagnostics

import java.net.URI

/**
 * Removes credentials and personally identifying paths from diagnostics text before it is shown or
 * written out (design §10).
 *
 * A faithful port of the reference extension's `src/common/diagnostics/sanitizer.ts`, including the
 * order the redactions run in: tokens, then passwords, then URL credentials, then file paths. The
 * order matters — a URL carrying a token in its query is caught by the token pass first, and the
 * path pass must run last so it cannot chew up a redaction placeholder written by an earlier pass.
 *
 * **This is the last line of defence, not the only one.** Nothing that collects diagnostics puts a
 * token into the report in the first place (`DiagnosticsSnapshot` holds `tokenConfigured: Boolean`,
 * never the token). This class exists for the text the plugin does not control: log lines, stack
 * traces and error messages that quote what they were given.
 *
 * Every pattern replaces **all** occurrences: Kotlin's `Regex.replace` is global by default, which
 * is what the reference's `/g` flag asks for.
 *
 * Stateless and immutable, so a single instance is safe to share across threads.
 */
class DiagnosticsSanitizer {

  /**
   * Redacts every pattern in [text]. Returns [text] unchanged when it holds nothing sensitive.
   *
   * [knownSecrets] are the secret values this installation actually holds — the configured token,
   * the OAuth access and refresh tokens. They are replaced **literally and first**, which is the
   * only rule here that does not depend on a token's shape: a self-managed instance can issue
   * tokens with no `glpat-` prefix at all, and an OAuth refresh token is an arbitrary string
   * (design §10.1). Everything else is a guess about format; this one is not.
   *
   * They are a parameter rather than a field on purpose. A sanitizer that stored the tokens would
   * be a data class holding secrets, which is exactly what `SecretRedactionConventionTest` is there
   * to police; keeping them in the call means this type never holds one.
   */
  fun sanitize(text: String, knownSecrets: Collection<String> = emptyList()): String {
    var sanitized = redactKnownSecrets(text, knownSecrets)
    sanitized = redactTokens(sanitized)
    sanitized = redactStructuredSecrets(sanitized)
    sanitized = redactPasswords(sanitized)
    sanitized = redactUrlCredentials(sanitized)
    return redactFilePaths(sanitized)
  }

  /**
   * Literal replacement of values this installation is known to hold.
   *
   * Uses the non-regex [String.replace], so a token containing regex metacharacters is matched as
   * written.
   *
   * **No minimum length.** An earlier draft skipped values under eight characters, reasoning that a
   * three-character "secret" would chew through ordinary prose. That got the priority backwards: an
   * over-redacted report is untidy, an under-redacted one leaks a credential. A short configured
   * token matches none of the shape-based patterns either, so skipping it left it in the export
   * with nothing else to catch it. Only blank values are skipped, because replacing the empty
   * string is not a redaction at all.
   */
  private fun redactKnownSecrets(text: String, secrets: Collection<String>): String =
    secrets
      .filter { it.isNotBlank() }
      .fold(text) { acc, secret -> acc.replace(secret, REDACTED_TOKEN) }

  /**
   * Secrets carried by structure rather than by shape: a JSON member or a query parameter whose
   * *key* says the value is sensitive. The language server logs HTTP traffic, so both forms reach
   * `language_server.log` and from there the export (design §10.1).
   */
  private fun redactStructuredSecrets(text: String): String {
    val withoutJson = JSON_SECRET.replace(text) { "\"${it.groupValues[1]}\": \"$REDACTED\"" }
    return QUERY_SECRET.replace(withoutJson) {
      "${it.groupValues[1]}${it.groupValues[2]}=$REDACTED"
    }
  }

  private fun redactTokens(text: String): String {
    var result = GITLAB_TOKEN.replace(text) { REDACTED_GITLAB_TOKEN }
    result = HEADER_TOKEN.replace(result) { "${it.groupValues[1]}: $REDACTED_TOKEN" }
    result = BEARER_TOKEN.replace(result) { "Bearer $REDACTED_TOKEN" }
    return BASIC_AUTH.replace(result) { "Basic $REDACTED_CREDENTIALS" }
  }

  /**
   * Keeps the `password:` / `password=` lead-in so the line still reads as a setting, and replaces
   * only the value. The lead-in is re-matched out of the hit rather than captured, exactly as the
   * reference does, because the value alternation makes a capturing group around the prefix awkward.
   */
  private fun redactPasswords(text: String): String =
    PASSWORD.replace(text) { match ->
      val prefix = PASSWORD_PREFIX.find(match.value)?.value ?: DEFAULT_PASSWORD_PREFIX
      "$prefix$REDACTED_PASSWORD"
    }

  /**
   * Replaces the user-info half of a URL while keeping the host and path, so a diagnostics reader
   * can still tell *which* remote misbehaved.
   *
   * Parsing is best-effort: a hit that [URI] rejects (the regex is deliberately looser than RFC
   * 3986) falls back to replacing just the `scheme://user:pass@` prefix, which is the part that
   * actually carries the secret.
   */
  private fun redactUrlCredentials(text: String): String =
    URL_CREDENTIALS.replace(text) { match ->
      val parsed = runCatching { URI(match.value) }.getOrNull()
      if (parsed?.host == null) fallbackUrlRedaction(match.value) else rebuildRedacted(parsed)
    }

  private fun rebuildRedacted(uri: URI): String = buildString {
    append(uri.scheme).append("://")
    append(REDACTED_USERNAME).append(':').append(REDACTED_PASSWORD).append('@')
    append(uri.host)
    if (uri.port != -1) append(':').append(uri.port)
    append(uri.rawPath.orEmpty())
    uri.rawQuery?.let { append('?').append(it) }
    uri.rawFragment?.let { append('#').append(it) }
  }

  private fun fallbackUrlRedaction(value: String): String =
    URL_CREDENTIALS_PREFIX.replace(value) { match ->
      "${match.groupValues[1]}://$REDACTED_USERNAME:$REDACTED_PASSWORD@"
    }

  /**
   * Redacts only the user-name segment of a home directory, keeping everything below it: the path
   * under the home directory is usually the whole point of the log line, and it identifies nobody.
   */
  private fun redactFilePaths(text: String): String {
    val withoutWindows = WINDOWS_HOME.replace(text) { match ->
      "${match.groupValues[1]}$REDACTED_USER${match.groupValues[2]}"
    }
    return UNIX_HOME.replace(withoutWindows) { match ->
      if (match.value.startsWith("/home/")) "/home/$REDACTED_USER" else "/Users/$REDACTED_USER"
    }
  }

  private companion object {
    // Compiled once. Building these per call would put a regex compile on every log line that
    // reaches an export.
    val GITLAB_TOKEN = Regex("gl(pat|ptt|oas|psc)-[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)
    val HEADER_TOKEN = Regex(
      "(PRIVATE-TOKEN|JOB-TOKEN|X-GITLAB-TOKEN)['\"\\s:=]+['\"]?[^\\s\"';,}]+['\"]?",
      RegexOption.IGNORE_CASE,
    )
    val BEARER_TOKEN = Regex("Bearer\\s+[a-zA-Z0-9._-]+", RegexOption.IGNORE_CASE)
    val BASIC_AUTH = Regex("Basic\\s+[a-zA-Z0-9+/=]+", RegexOption.IGNORE_CASE)

    // The separator is required so prose like "Invalid password format" survives intact.
    val PASSWORD = Regex(
      "\\bpassword['\"\\s]*[:=]\\s*(?:(['\"]).*?\\1|[^\\s;,}]+)",
      RegexOption.IGNORE_CASE,
    )
    val PASSWORD_PREFIX = Regex("\\bpassword['\"\\s]*[:=]\\s*", RegexOption.IGNORE_CASE)

    // Structure-carried secrets (design §10.1). `token` is listed after the more specific names so
    // the alternation prefers them; the replacement writes the matched key back either way.
    val JSON_SECRET = Regex(
      "\"(access_token|refresh_token|private_token|token|password|secret)\"\\s*:\\s*\"[^\"]*\"",
      RegexOption.IGNORE_CASE,
    )
    val QUERY_SECRET = Regex(
      "([?&])(access_token|private_token|token|password)=[^&\\s\"']*",
      RegexOption.IGNORE_CASE,
    )

    val URL_CREDENTIALS = Regex("https?://[^:/@\\s]+:[^@\\s]+@[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    val URL_CREDENTIALS_PREFIX = Regex("(https?)://[^:/@\\s]+:[^@\\s]+@", RegexOption.IGNORE_CASE)

    val WINDOWS_HOME = Regex(
      "([A-Z]:\\\\Users\\\\)[^\\\\\\s\"']+((?:\\\\[^\\s\"']*)?)",
      RegexOption.IGNORE_CASE,
    )
    val UNIX_HOME = Regex("/(?:home|Users)/[^/\\s\"']+", RegexOption.IGNORE_CASE)

    const val DEFAULT_PASSWORD_PREFIX = "password: "
    const val REDACTED = "[REDACTED]"
    const val REDACTED_GITLAB_TOKEN = "[REDACTED_GITLAB_TOKEN]"
    const val REDACTED_TOKEN = "[REDACTED_TOKEN]"
    const val REDACTED_CREDENTIALS = "[REDACTED_CREDENTIALS]"
    const val REDACTED_PASSWORD = "[REDACTED_PASSWORD]"
    const val REDACTED_USERNAME = "[REDACTED_USERNAME]"
    const val REDACTED_USER = "[REDACTED_USER]"
  }
}
