package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.webview.ThemeProvider
import com.gitlab.eclipse.lsp.webview.WebviewLoadCoordinator
import com.gitlab.eclipse.lsp.webview.WebviewLoadPipeline
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.system.SystemUtils
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.custom.StackLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control

/**
 * The SWT side of a webview surface. Design §7.2.
 *
 * It bundles the sinks a [WebviewLoadPipeline] drives and calls [load]. Nothing here decides
 * *which* [WebviewLoadCoordinator.Outcome] is applied, or in what order the sinks run; that is
 * entirely the pipeline's. What is decided here is widget-local, and the case worth knowing about
 * is the pair of guards that turn a `Browser`'s boolean refusal into the throw design §7.2b reads
 * as failure. Every member here is confined to the UI thread (design §15).
 *
 * [coordinator] is taken rather than a ready-made [WebviewLoadPipeline], which cannot exist before
 * the widgets its sinks capture: a deviation from design §7.2's listing. One coordinator still
 * belongs to exactly one pipeline (design §7.2a).
 */
class WebviewBrowserHost(
  parent: Composite,
  coordinator: WebviewLoadCoordinator,
  setTitle: (String) -> Unit,
) {
  private val logger = logger<WebviewBrowserHost>()

  private val stackLayout = StackLayout()
  private val container = Composite(parent, SWT.NONE).apply { layout = stackLayout }

  /**
   * A refusal here is recorded rather than raised: throwing would abort the caller's
   * `createPartControl` and cost the whole surface, where this page is transient and a blank one
   * costs at most an empty background while a load runs. The record is wrapped for that same
   * reason — it runs in that same constructor, so a platform log that is already gone would
   * otherwise inflict the cost this chose not to inflict.
   */
  private val loadingPage = newBrowser().apply {
    if (!setText(themedHtml("Loading..."))) {
      try {
        logger.warn("The browser refused the loading page.")
      } catch (_: Throwable) {
        // There is nowhere left to record this: the log is the thing that failed.
      }
    }
  }
  private val messagePage = newBrowser()
  private val contentPage = newBrowser()

  /**
   * The control that [setLoadingVisible] returns to, and the answer to `hasStableContent`. Design
   * §7.2a excludes the loading page from that answer, so [loadingPage] is never written here.
   */
  private var stableControl: Control? = null

  private val pipeline = WebviewLoadPipeline(
    coordinator = coordinator,
    // Design §7.2b makes a throw the only way either of these two can report that its page is not
    // on screen, so a refused `Browser` call is raised rather than dropped. Design §17 keeps the
    // url out of the message.
    showUrl = { url ->
      if (!contentPage.setUrl(url)) error("The browser refused the resolved url.")
      show(contentPage)
    },
    showMessage = { text ->
      if (!messagePage.setText(themedHtml(text))) error("The browser refused the message page.")
      show(messagePage)
    },
    setTitle = setTitle,
    hasStableContent = { stableControl != null },
    setLoadingVisible = { visible ->
      stackLayout.topControl = if (visible) loadingPage else stableControl
      container.layout()
    },
    // Design §7.2c mechanism 2, and its requirement that this cannot throw: `Widget.isDisposed` is
    // a field test.
    isAlive = { !container.isDisposed },
  )

  /** Design §7.2b / §8.1, for [WebviewEditorOpener]. */
  val displayedSession: LanguageServerSession?
    get() = pipeline.displayedSession

  /** Design §7.2. */
  fun load(id: String, queryParams: Map<String, String> = emptyMap()) {
    pipeline.load(id, queryParams)
  }

  fun setFocus(): Boolean = stackLayout.topControl?.setFocus() ?: false

  /**
   * Design §7.2c needs both of its mechanisms, so this runs both. The pipeline goes first: it is
   * two field writes and cannot fail, whereas a widget dispose that throws part-way would otherwise
   * leave mechanism 1 undone.
   */
  fun dispose() {
    pipeline.dispose()
    container.dispose()
  }

  /**
   * Puts [control] on top and records it as the stable content, which is where
   * `setLoadingVisible(false)` lands once design §7.2b's `finally` runs: on the page a sink has
   * just applied, or on the page from before when that sink threw.
   */
  private fun show(control: Control) {
    stableControl = control
    stackLayout.topControl = control
    container.layout()
  }

  /** The same choice as `LanguageServerBrowserView.newBrowser` (`:324-327`). */
  private fun newBrowser(): Browser {
    val browserStyle = if (SystemUtils.isWindows()) SWT.EDGE else SWT.WEBKIT
    return Browser(container, browserStyle)
  }

  /** The same page as `LanguageServerBrowserView.themedHtml` (`:329-355`). */
  private fun themedHtml(message: String): String {
    val colors = ThemeProvider.currentTheme()

    return """
      <!doctype html>
      <html lang="en">
      <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>GitLab</title>
      </head>
      <style>
        * {
          background: ${colors.styles["--editor-background-alternative"]};
          color: ${colors.styles["--editor-foreground"]};

          font-family: ${colors.styles["--editor-font-family"]};
          font-size: ${colors.styles["--editor-font-size"]};
          font-weight: ${colors.styles["--editor-font-style"]};
        }
      </style>
      <body>
          <p>$message</p>
      </body>
      </html>
    """.trimIndent()
  }
}
