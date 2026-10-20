package com.gitlab.eclipse.views.webview

import java.net.URI
import java.net.URISyntaxException

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val WEBVIEW_PATH_PREFIX = "/webview/"
private const val CSRF_PARAMETER = "_csrf"
private const val EXTERNAL_INTERVAL_NANOS = 1_000_000_000L
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

/**
 * Keeps an interactive language server page (the Agent Platform view) on its own origin without
 * freezing it the way [TopLevelNavigationGuard] does after `completed` (design AGENT-02 §5.1a).
 *
 * The base origin is the one of the last [expectLoad]. Every location is judged in the fixed order
 * **ALLOW → RELOAD → REFUSE → EXTERNAL**:
 * - ALLOW: `about:blank` (Edge's start page), the expected url itself (fragment and a trailing `/`
 *   ignored, raw query compared), or a same-origin `/webview/` page that still carries `_csrf`.
 * - RELOAD: a same-origin `/webview/` page without `_csrf` — the engine's own Reload / Back reads
 *   the address the app left after `replaceState` stripped the token, and only a fresh metadata
 *   resolution can give the page a token again. Once per [expectLoad] generation: a second one in
 *   the same generation is a REFUSE recorded as a warning, so a page that keeps losing its token
 *   cannot loop.
 * - REFUSE: any other same-origin path (an agent's relative link such as `/group/proj/-/issues/1`),
 *   any loopback host on any port (after a language server restart, Back offers the old port's
 *   token-bearing url, which must not reach an external browser), a non-http(s) scheme, an
 *   unparsable location, `null`.
 * - EXTERNAL: any other http(s) url, at most once per second by [clock] (sub-frame navigations also
 *   arrive at `changing`; this bounds what a page could open); otherwise REFUSE.
 *
 * `pushState` / `replaceState` / socket.io / fetch never fire `changing`, so the app's own routing
 * is untouched. The engine's context menu stays (copy / paste in the chat input); new windows are
 * refused. Confined to the UI thread; it does not log, and neither its [toString] nor any
 * [RefusalNote] names a location.
 */
class LanguageServerOriginNavigationGuard(
  private val clock: () -> Long = System::nanoTime,
) : WebviewNavigationPolicy {
  /** The parsed url of the last [expectLoad]; `null` before the first or when it did not parse. */
  private var expected: Target? = null

  /** Whether this generation has already asked for its one reload. */
  private var reloadRequested = false

  /** [clock] at the last EXTERNAL, or `null` before the first. */
  private var lastExternalAt: Long? = null

  /** Whether the last REFUSE was a repeated RELOAD, which the host records as a warning. */
  private var lastRefusalRepeatedReload = false

  override val suppressContextMenu: Boolean = false

  override val refuseNewWindows: Boolean = true

  override fun expectLoad(url: String) {
    expected = Target.parse(url)
    reloadRequested = false
  }

  /** The base stays in force for the life of the page, so `completed` changes nothing. */
  override fun loadCompleted() = Unit

  override fun decide(location: String?): NavigationDecision {
    lastRefusalRepeatedReload = false
    if (location == null) return NavigationDecision.Refuse
    if (location.equals(ABOUT_BLANK, ignoreCase = true)) return NavigationDecision.Allow
    val target = Target.parse(location) ?: return NavigationDecision.Refuse
    if (target.scheme != "http" && target.scheme != "https") return NavigationDecision.Refuse

    val base = expected
    if (base != null && target == base) return NavigationDecision.Allow
    if (base != null && target.sameOrigin(base)) return sameOrigin(target)
    if (target.host in LOOPBACK_HOSTS) return NavigationDecision.Refuse
    return external(location)
  }

  override fun refusalNote(): RefusalNote = if (lastRefusalRepeatedReload) RELOAD_REPEATED else BLOCKED

  private fun sameOrigin(target: Target): NavigationDecision {
    if (!target.path.startsWith(WEBVIEW_PATH_PREFIX)) return NavigationDecision.Refuse
    if (target.hasCsrf) return NavigationDecision.Allow
    if (reloadRequested) {
      lastRefusalRepeatedReload = true
      return NavigationDecision.Refuse
    }
    reloadRequested = true
    return NavigationDecision.Reload
  }

  private fun external(location: String): NavigationDecision {
    val now = clock()
    val last = lastExternalAt
    if (last != null && now - last < EXTERNAL_INTERVAL_NANOS) return NavigationDecision.Refuse
    lastExternalAt = now
    return NavigationDecision.External(location)
  }

  override fun toString(): String = "LanguageServerOriginNavigationGuard"

  /**
   * The part of a url that identifies a page: everything but the fragment and a trailing `/`. Its
   * query carries the CSRF token, so [toString] names none of it.
   */
  private data class Target(val scheme: String, val host: String, val port: Int, val path: String, val query: String) {
    val hasCsrf: Boolean
      get() = query.split('&').any { it.substringBefore('=') == CSRF_PARAMETER }

    fun sameOrigin(other: Target): Boolean = scheme == other.scheme && host == other.host && port == other.port

    override fun toString(): String = "Target(scheme=$scheme)"

    companion object {
      fun parse(url: String): Target? {
        val uri = try {
          URI(url)
        } catch (_: URISyntaxException) {
          return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
          uri.port != -1 -> uri.port
          scheme == "http" -> HTTP_DEFAULT_PORT
          scheme == "https" -> HTTPS_DEFAULT_PORT
          else -> -1
        }
        return Target(scheme, host, port, uri.rawPath.orEmpty().removeSuffix("/"), uri.rawQuery.orEmpty())
      }
    }
  }

  private companion object {
    const val ABOUT_BLANK = "about:blank"
    val RELOAD_REPEATED = RefusalNote(
      "Agent Platform webview: reload already requested for this load; refused the navigation.",
      isWarning = true,
    )
    val BLOCKED = RefusalNote("Blocked a webview navigation away from the language server page.")
  }
}
