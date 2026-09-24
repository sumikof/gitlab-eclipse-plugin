package com.gitlab.eclipse.views.webview

import java.net.URI
import java.net.URISyntaxException

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443

/**
 * Decides which navigations a webview `Browser` may perform: only the load its host asked for.
 *
 * A link whose text is formatted (`[**x**](https://…)`) is not intercepted by the vulnerability details bundle's
 * `handleLinkClick`, because the click target is the inner element rather than the `<a>`; the browser's default
 * action would then navigate the webview itself away from the language server's page (PR #89 known limitation 8).
 * Links that are intercepted reach the plugin as `$/gitlab/openUrl` instead, so refusing every other navigation
 * costs the page nothing it needs.
 *
 * The host calls [expectLoad] immediately before `Browser.setUrl`, which opens a window in which only that url
 * (same scheme, host, port, path and raw query; a trailing `/` on the path and the fragment ignored, so a
 * `/webview/<id>` → `/webview/<id>/` redirect, which keeps the query, still lands) may load. The query is compared
 * because the page is interactive before `completed`: a formatted relative link such as `[**x**](?error=1)` would
 * otherwise pass as the expected load, dropping or replacing the `_csrf` token (PR #90 review). Comparing it raw is
 * safe: the language server's `@fastify/csrf` token uses only URL-safe characters and `WebviewQueryBuilder`
 * percent-encodes everything else, so the engine reports the query exactly as it was set. Letting that url through admits the load, and
 * only then does [loadCompleted] close the window: a `completed` that arrives first belongs to something else
 * (Edge starts on `about:blank` and queues `setUrl` until it is ready) and leaves the window open. Once closed,
 * every navigation is refused, the same url and a fragment-only change included.
 *
 * There is no frame flag: on WebKitGTK `changing` never sets `LocationEvent.top` (only the `changed` path does),
 * so every navigation is judged. The page has no sub-frames, and refusing one would be the safe direction.
 *
 * Confined to the UI thread, like the `Browser` whose listeners call it, so its state is plain fields. It does not
 * log: the host records a refusal, without the location.
 */
class TopLevelNavigationGuard {
  /** Whether [expectLoad] opened a window that [loadCompleted] has not yet closed. */
  private var windowOpen = false

  /** The page the open window admits; `null` when [expectLoad] was given a url that does not parse. */
  private var expected: Target? = null

  /** Whether [allows] has let the expected load through since the last [expectLoad]. */
  private var admitted = false

  /**
   * True while the window is open for a url that did not parse: every navigation is then refused, the host's own
   * load included, which the host records apart from an ordinary refusal.
   */
  val expectedLoadUnparseable: Boolean
    get() = windowOpen && expected == null

  /** Opens the window for [url], replacing any earlier one and its admission. */
  fun expectLoad(url: String) {
    windowOpen = true
    expected = Target.parse(url)
    admitted = false
  }

  /** Closes the window, but only once the expected load has been admitted. */
  fun loadCompleted() {
    if (!admitted) return
    windowOpen = false
    expected = null
    admitted = false
  }

  fun allows(location: String?): Boolean {
    val window = expected?.takeIf { windowOpen } ?: return false
    val target = location?.let(Target::parse) ?: return false
    if (target != window) return false
    admitted = true
    return true
  }

  /**
   * The part of a url that identifies the load: everything but the fragment and a trailing `/`. Its query carries the
   * CSRF token, so [toString] names none of it.
   */
  private data class Target(val scheme: String, val host: String, val port: Int, val path: String, val query: String) {
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
}

/**
 * The guard the webview editor tab for [webviewId] navigates under, or `null` for a webview that keeps the
 * browser's default behaviour. Only the vulnerability details page renders untrusted links; a fresh guard per tab.
 */
fun navigationGuardFor(webviewId: String): TopLevelNavigationGuard? =
  if (webviewId == WebviewEditorInput.SECURITY_VULN_DETAILS_WEBVIEW_ID) TopLevelNavigationGuard() else null
