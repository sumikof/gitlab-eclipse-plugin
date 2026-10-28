package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWTError
import org.eclipse.ui.PlatformUI
import java.net.URL
import java.util.concurrent.CompletableFuture

/** The one notice for a link that could not be opened, whether refused or failed (D-D2). Never carries the link. */
const val BROWSER_OPEN_FAILED_MESSAGE = "Couldn't open the link in the browser."

/**
 * The ONE gate through which this plugin hands a link to the external browser (D8a): only an http/https link with a
 * host and no userinfo passes ([browserUrlVerdict]); nothing else reaches the browser. The decision is made on the
 * calling thread, before any UI hop.
 *
 * Logs a fixed text plus the refusal reason or an exception class name — never the link, any part of it (not even a
 * scheme label, D-D6), or an exception message, and never a throwable (the platform log would write its message).
 *
 * **`SWTError` is caught alongside `Exception`**: it extends `Error`, and escaping an `asyncExec` runnable it would
 * land in the SWT event loop, which logs it with its message and stack trace. `OutOfMemoryError` and the rest are
 * deliberately not caught. `IWebBrowser.openURL` returns `void` and signals failure by throwing `PartInitException`,
 * as does `getExternalBrowser` itself (verified against `org.eclipse.ui.workbench-3.137.0.jar`), so returning
 * normally is what "it launched" means.
 *
 * Why [openAsync] takes `String?` while [open] / [openChecked] take `String`: [openAsync] serves the language server's
 * `window/showDocument`, whose URI may be absent, and a null is just one more refused link there. Every caller of the
 * other two holds a non-null URL, so widening them would only invite a null the gate would refuse anyway.
 *
 * @property onUiThread the UI-thread hop; defaults to `currentDisplay.asyncExec`. Never `syncExec`: [open] and
 *   [openAsync] are called from lsp4j dispatch and background threads.
 * @property openInBrowser opens one URL; defaults to the workbench external browser. **UI thread only.**
 * @property notify shows the user a notice from any thread; defaults to [NotificationUtils.show].
 */
class BrowserLauncher internal constructor(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val openInBrowser: (URL) -> Unit = { PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(it) },
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  // Lazy on purpose: the launcher is built as a constructor default of its callers, potentially before the platform
  // log exists (and before a test's log mock is installed).
  private val log by lazy { logger<BrowserLauncher>() }

  /**
   * Opens [url] from any thread, fire-and-forget, through one `asyncExec` hop. A refused link, a browser failure and
   * an unreachable UI thread are each logged and shown to the user once as [BROWSER_OPEN_FAILED_MESSAGE]. Never throws.
   */
  fun open(url: String) {
    launchLater(url) { launched -> if (!launched) notify(BROWSER_OPEN_FAILED_MESSAGE) }
  }

  /**
   * Opens [url] synchronously on the CURRENT thread, which MUST be the UI thread (workbench browser support requires
   * it), returning whether the browser launched. Logs a refusal or failure but does not notify: the caller reacts to
   * `false` with its own notice.
   */
  fun openChecked(url: String): Boolean = allowedOrNull(url)?.let(::launchNow) ?: false

  /**
   * Opens [url] from any thread through one `asyncExec` hop, completing with whether the browser launched. Completes
   * `false` — never exceptionally — for a refused link (before any hop), a browser failure and an unreachable UI
   * thread. Logs, does not notify. The future stays pending only if a queued UI runnable is never dispatched at all.
   */
  fun openAsync(url: String?): CompletableFuture<Boolean> {
    val outcome = CompletableFuture<Boolean>()
    launchLater(url) { outcome.complete(it) }
    return outcome
  }

  private fun launchLater(url: String?, onOutcome: (Boolean) -> Unit) {
    val target = allowedOrNull(url) ?: return onOutcome(false)
    try {
      onUiThread(Runnable { onOutcome(launchNow(target)) })
    } catch (e: Exception) {
      // The display lookup throws IllegalStateException once the workbench is torn down, and asyncExec throws
      // SWTException on a disposed display. Neither may travel back to the caller (often a dispatch thread).
      log.warn("BrowserLauncher: could not reach the UI thread: ${e.javaClass.name}")
      onOutcome(false)
    }
  }

  private fun allowedOrNull(url: String?): URL? =
    when (val verdict = browserUrlVerdict(url)) {
      is BrowserUrlVerdict.Allowed -> verdict.url
      is BrowserUrlVerdict.Refused -> {
        log.warn("BrowserLauncher: refused a link outside the http/https policy; reason=${verdict.reason}")
        null
      }
    }

  /** One browser launch with every failure contained. UI thread only. */
  private fun launchNow(url: URL): Boolean =
    try {
      openInBrowser(url)
      true
    } catch (e: SWTError) {
      launchFailed(e)
    } catch (e: Exception) {
      launchFailed(e)
    }

  private fun launchFailed(e: Throwable): Boolean {
    log.warn("BrowserLauncher: the browser did not open the link: ${e.javaClass.name}")
    return false
  }
}
