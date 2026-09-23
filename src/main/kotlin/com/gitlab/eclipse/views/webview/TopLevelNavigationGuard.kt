package com.gitlab.eclipse.views.webview

import java.net.URI
import java.net.URISyntaxException

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443

/**
 * Decides which top-level navigations a webview `Browser` may perform: only the load its host asked for.
 *
 * A link whose text is formatted (`[**x**](https://…)`) is not intercepted by the vulnerability details bundle's
 * `handleLinkClick`, because the click target is the inner element rather than the `<a>`; the browser's default
 * action would then navigate the webview itself away from the language server's page (PR #89 known limitation 8).
 * Links that are intercepted reach the plugin as `$/gitlab/openUrl` instead, so refusing every other top-level
 * navigation costs the page nothing it needs.
 *
 * The host calls [expectLoad] immediately before `Browser.setUrl`, which opens a window in which only that url
 * (same scheme, host, port and path; trailing `/`, query and fragment ignored, so the server's
 * `/webview/<id>` → `/webview/<id>/` redirect still lands) may load at top level. [loadCompleted] closes the window;
 * from then on every top-level navigation is refused, the same url and a fragment-only change included.
 * Sub-frames are out of scope: the page has none, and the details it renders are escaped HTML.
 *
 * Confined to the UI thread, like the `Browser` whose listeners call it, so its state is plain fields. It does not
 * log: the host records a refusal, without the location.
 */
class TopLevelNavigationGuard {
  private var expected: Target? = null

  /** Opens the initial-load window for [url], replacing any earlier one. */
  fun expectLoad(url: String) {
    expected = Target.parse(url)
  }

  /** Closes the initial-load window. */
  fun loadCompleted() {
    expected = null
  }

  fun allows(location: String?, topLevel: Boolean): Boolean {
    if (!topLevel) return true
    val window = expected ?: return false
    val target = location?.let(Target::parse) ?: return false
    return target == window
  }

  /** The part of a url that identifies the page: never the query or fragment, which carry the CSRF token. */
  private data class Target(val scheme: String, val host: String, val port: Int, val path: String) {
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
        return Target(scheme, host, port, uri.rawPath.orEmpty().removeSuffix("/"))
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
