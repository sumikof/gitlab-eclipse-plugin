package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.security.details.OpenableLink
import com.gitlab.eclipse.security.details.VulnerabilityLinkPolicy
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWTError
import org.eclipse.swt.program.Program

/**
 * Opens a link the vulnerability details webview asked for — `$/gitlab/openUrl`, which the language
 * server sends on the webview's behalf when the user clicks a link in a finding.
 *
 * The link is re-checked here by [VulnerabilityLinkPolicy] whatever the webview or the projection
 * already did: the host is the last point where a `javascript:` link, a disguised host or the
 * webview's own tokened loopback URL can still be stopped. A refused link produces one warn line
 * naming its scheme and nothing else.
 *
 * - A web link goes to [ShowDocumentLauncher.show], which checks it once more, hops to the UI thread
 *   and contains and logs its own failures; its future is not awaited.
 * - A `mailto:` link goes to [launchProgram] on the UI thread.
 *
 * Like [ShowDocumentLauncher], nothing here ever logs the link, any part of it, or an exception
 * message — only a scheme or an exception class name. [open] never throws and never blocks on the
 * UI thread (`asyncExec`, **never `syncExec`** — it runs on a thread lsp4j dispatches from).
 *
 * @property showDocument opens web links
 * @property onUiThread the UI-thread hop; defaults to `currentDisplay.asyncExec`
 * @property launchProgram hands a `mailto:` link to the system; defaults to SWT's `Program.launch`.
 *   **UI thread only.**
 */
class OpenUrlHandler(
  private val showDocument: ShowDocumentLauncher,
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val launchProgram: (String) -> Boolean = { Program.launch(it) },
) {
  // Lazy for the same reason as ShowDocumentLauncher's: built before the platform log may exist.
  private val log by lazy { logger<OpenUrlHandler>() }

  /** Opens [url] if the policy allows it; otherwise logs its scheme and does nothing. */
  fun open(url: String?) {
    when (val link = VulnerabilityLinkPolicy.classify(url)) {
      null -> log.warn(
        "openUrl: refused a link from the vulnerability details webview; " +
          "scheme=${VulnerabilityLinkPolicy.schemeForLog(url)}"
      )
      is OpenableLink.Web -> showDocument.show(link.url)
      is OpenableLink.Mail -> launchMail(link.url)
    }
  }

  private fun launchMail(url: String) {
    try {
      onUiThread(Runnable { launchNow(url) })
    } catch (e: Exception) {
      // No display (workbench torn down) or a disposed one: nothing to open it with, nothing to
      // send back up the dispatch thread.
      log.warn("openUrl: could not reach the UI thread: ${e.javaClass.name}")
    }
  }

  /**
   * One mail-client launch with every failure contained. UI thread only. `SWTError` is an `Error`,
   * not an `Exception`, and escaping this `asyncExec` runnable it would reach the SWT event loop,
   * which logs it with its message — see `ShowDocumentLauncher.openNow`.
   */
  private fun launchNow(url: String) {
    try {
      if (!launchProgram(url)) log.warn("openUrl: the mail client did not open the link")
    } catch (e: SWTError) {
      launchFailed(e)
    } catch (e: Exception) {
      launchFailed(e)
    }
  }

  private fun launchFailed(e: Throwable) {
    log.warn("openUrl: the mail client did not open the link: ${e.javaClass.name}")
  }
}
