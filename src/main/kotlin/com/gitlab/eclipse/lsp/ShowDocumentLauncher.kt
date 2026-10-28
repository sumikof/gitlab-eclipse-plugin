package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.navigation.schemeLabel
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWTError
import org.eclipse.ui.PlatformUI
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Opens a URI the language server supplies — `window/showDocument` — in the user's external browser.
 *
 * A `window/showDocument` URI comes from the server and can carry a signature or a token in its
 * query or fragment, and a log line outlives the request — so nothing here ever writes the URI, any
 * part of it, or an exception message. Failures are logged by exception class name, a refusal by
 * scheme label alone. (Still its own hop and policy check for now; routing it through
 * [com.gitlab.eclipse.navigation.BrowserLauncher.openAsync], which has the same contract, is #142 D8a.)
 *
 * The URI is refused before the browser is touched, by [isBrowsableExternalUrl]: absolute http/https
 * with a host and no userinfo, so `javascript:`, `file:` and a host disguised as userinfo never
 * reach `openURL`.
 *
 * `external == false` (open in an editor rather than a browser) is not sent by the current server;
 * if it ever arrives it belongs to the `$/gitlab/openFile` path ([WorkspaceFileOpener]), not here.
 *
 * Runs on the calling (lsp4j dispatch) thread and returns at once: the browser call hops to the UI
 * thread with `asyncExec`. **Never `syncExec`** — the dispatch thread would deadlock against a UI
 * thread waiting on it. The returned future never completes exceptionally, and nothing — not even
 * an `SWTError` — escapes into the event loop; the future can only stay pending if a queued UI
 * runnable is never dispatched at all, which the caller's own timeout covers.
 *
 * @property onUiThread the UI-thread hop; defaults to `currentDisplay.asyncExec`
 * @property openInBrowser opens one URL and says whether it launched; defaults to the workbench
 *   external browser. **UI thread only.**
 */
class ShowDocumentLauncher(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val openInBrowser: (String) -> Boolean = { openInExternalBrowser(it) },
) {
  // Lazy on purpose: the launcher is built as a constructor default of its caller, potentially
  // before the platform log exists (and before a test's log mock is installed).
  private val log by lazy { logger<ShowDocumentLauncher>() }

  /**
   * Opens [uri] in the external browser, completing with whether it launched.
   *
   * Completes `false` — never exceptionally — for a URI the policy refuses, for a browser that
   * declines or throws, and for a UI thread that cannot be reached.
   */
  fun show(uri: String?): CompletableFuture<Boolean> {
    val url = uri?.takeIf { isBrowsableExternalUrl(it) } ?: return refused(uri)

    val outcome = CompletableFuture<Boolean>()
    try {
      onUiThread(Runnable { outcome.complete(openNow(url)) })
    } catch (e: Exception) {
      // The display lookup throws IllegalStateException once the workbench is torn down, and
      // asyncExec throws SWTException on a disposed display. Neither may leave the future pending,
      // and neither may travel back up the dispatch thread.
      log.warn("showDocument: could not reach the UI thread: ${e.javaClass.name}")
      outcome.complete(false)
    }
    return outcome
  }

  /**
   * One browser launch with every failure contained. UI thread only. Logs no URI, no message.
   *
   * **`SWTError` extends `Error`, not `Exception`** — the same reason `ClipboardWriter.tryWriteNow`
   * catches it. This runs inside an `asyncExec` runnable, so an escaping error would land in the
   * SWT event loop, where the workbench logs it *with its message and stack trace* and may raise
   * the internal-error dialog: the very leak this class exists to prevent, in a place this class
   * cannot reach. `OutOfMemoryError` and the rest are deliberately not caught. `SWTException` is a
   * `RuntimeException` and is covered by the `Exception` arm, as is `PartInitException`.
   */
  private fun openNow(url: String): Boolean =
    try {
      openInBrowser(url)
    } catch (e: SWTError) {
      launchFailed(e)
    } catch (e: Exception) {
      launchFailed(e)
    }

  private fun launchFailed(e: Throwable): Boolean {
    log.warn("showDocument: the browser did not open the URI: ${e.javaClass.name}")
    return false
  }

  private fun refused(uri: String?): CompletableFuture<Boolean> {
    log.warn("showDocument: refused a URI outside the external URL policy; scheme=${schemeOf(uri)}")
    return CompletableFuture.completedFuture(false)
  }
}

/**
 * The scheme of [uri] as a label safe to log — the only part of a refused URI that is ever logged. Delegates to
 * [com.gitlab.eclipse.navigation.schemeLabel]: a scheme from a fixed vocabulary, else `other` (the URI scheme grammar
 * admits token-shaped strings such as `glpat-…`), or `none` / `relative` / `unparseable`.
 */
internal fun schemeOf(uri: String?): String = schemeLabel(uri)

/**
 * Production default for [ShowDocumentLauncher.openInBrowser]. **UI thread only.**
 *
 * `IWebBrowser.openURL` returns `void` and signals failure by throwing `PartInitException` (verified
 * against `org.eclipse.ui.workbench-3.137.0.jar`), as does `getExternalBrowser` itself, so reaching
 * the end of this function is what "it launched" means. `URI.create`/`toURL` can throw too
 * (`IllegalArgumentException` / `MalformedURLException`); all of it is contained by the caller.
 */
internal fun openInExternalBrowser(url: String): Boolean {
  PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(URI.create(url).toURL())
  return true
}
