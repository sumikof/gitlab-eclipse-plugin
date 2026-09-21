package com.gitlab.eclipse.diagnostics

/**
 * The version the language server reported in its `initialize` response.
 *
 * The bundled version is pinned in `package.json`, which is a build input and is not on the
 * runtime classpath; reading it back would need a generated constant, and generating one means
 * editing `build.gradle.kts`, which the phase constraints forbid. The server already tells us,
 * so this records what it said.
 *
 * Unset until the first successful initialize — the report prints `Not available` then, which is
 * the honest answer for a server that has not come up.
 */
object LanguageServerVersionState {

  @Volatile
  private var version: String? = null

  /** Records the version from an `initialize` result. A blank or absent value clears nothing. */
  fun record(reported: String?) {
    if (!reported.isNullOrBlank()) version = reported
  }

  /** The last reported version, or `null` if the server has never successfully initialized. */
  fun current(): String? = version
}
