package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.schemeLabel
import java.util.concurrent.CompletableFuture

/**
 * Opens a URI the language server supplies — `window/showDocument` — in the user's external browser,
 * through the plugin's one browser gate, [BrowserLauncher.openAsync] (#142 D8a).
 *
 * A `window/showDocument` URI comes from the server and can carry a signature or a token in its
 * query or fragment, and a log line outlives the request — so nothing on this path ever writes the
 * URI, any part of it, or an exception message: the gate logs a refusal by reason alone (D-D6), and a
 * failure by exception class name.
 *
 * The gate refuses the URI before any UI hop — absolute http/https with a host and no userinfo only,
 * so `javascript:`, `file:` and a host disguised as userinfo never reach the browser.
 *
 * `external == false` (open in an editor rather than a browser) is not sent by the current server;
 * if it ever arrives it belongs to the `$/gitlab/openFile` path ([WorkspaceFileOpener]), not here.
 *
 * Runs on the calling (lsp4j dispatch) thread and returns at once: the gate hops to the UI thread
 * with `asyncExec`. **Never `syncExec`** — the dispatch thread would deadlock against a UI thread
 * waiting on it. The returned future never completes exceptionally, and does not notify the user —
 * the server is the one told; it can only stay pending if a queued UI runnable is never dispatched
 * at all, which the caller's own timeout covers.
 *
 * @property browser the gate; defaults to the production [BrowserLauncher]
 */
class ShowDocumentLauncher(
  internal val browser: BrowserLauncher = BrowserLauncher(),
) {
  /**
   * Opens [uri] in the external browser, completing with whether it launched.
   *
   * Completes `false` — never exceptionally — for a URI the policy refuses, for a browser that
   * throws, and for a UI thread that cannot be reached.
   */
  fun show(uri: String?): CompletableFuture<Boolean> = browser.openAsync(uri)
}

/**
 * The scheme of [uri] as a label safe to log — the only part of a refused URI that `OpenUrlHandler` (via
 * `VulnerabilityLinkPolicy.schemeForLog`) ever logs; the browser gate itself logs no scheme (D-D6). Delegates to
 * [com.gitlab.eclipse.navigation.schemeLabel]: a scheme from a fixed vocabulary, else `other` (the URI scheme grammar
 * admits token-shaped strings such as `glpat-…`), or `none` / `relative` / `unparseable`.
 */
internal fun schemeOf(uri: String?): String = schemeLabel(uri)
