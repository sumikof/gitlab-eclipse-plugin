package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.webview.WebviewLoadCoordinator
import com.gitlab.eclipse.lsp.webview.WebviewUriResolver
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.part.ViewPart

/**
 * Hosts the `agentic-tabs` webview. Design §7.2 / §7.5.
 *
 * The surface is read-only: design §5.4a records that the Language Server's `agentic-tabs` plugin
 * registers no host-bound message route, so there is no client to wire here.
 */
class AgenticTabsView : ViewPart() {
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  private var host: WebviewBrowserHost? = null

  override fun createPartControl(parent: Composite) {
    // Design §8.1's `asyncExec`, taken from the display that owns these widgets. The
    // WebviewLoadCoordinator contract allows this to fail only once that display is gone, which is
    // the one way `Display.asyncExec` fails.
    val display = parent.display
    val coordinator = WebviewLoadCoordinator(
      resolver = WebviewUriResolver(languageServerWrapper),
      wrapper = languageServerWrapper,
      onUiThread = { display.asyncExec(it) },
    )

    val created = WebviewBrowserHost(parent, coordinator) { partName = it }
    host = created
    // Design §23 U-4: resolution happens when the view is opened, and there is no re-resolution
    // hook. A user who opens the view before the language server is up reopens it.
    created.load(WEBVIEW_ID)
  }

  override fun setFocus() {
    host?.setFocus()
  }

  override fun dispose() {
    host?.dispose()
    super.dispose()
  }

  companion object {
    const val VIEW_ID = "com.gitlab.eclipse.views.webview.AgenticTabsView"

    /** Design §5.2's measured id. */
    private const val WEBVIEW_ID = "agentic-tabs"
  }
}
