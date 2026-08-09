package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.webview.WebviewLoadCoordinator
import com.gitlab.eclipse.lsp.webview.WebviewUriResolver
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorSite
import org.eclipse.ui.PartInitException
import org.eclipse.ui.part.EditorPart

/**
 * Hosts one webview in the editor area. Design §7.3.
 *
 * The tab is a viewer: it saves nothing, and design §7.2's [WebviewBrowserHost] is the whole of its
 * content. Everything here runs on the UI thread (design §15).
 */
class WebviewEditorPart : EditorPart(), WebviewEditorSurface {
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  private var host: WebviewBrowserHost? = null

  override fun init(site: IEditorSite, input: IEditorInput) {
    if (input !is WebviewEditorInput) {
      throw PartInitException("A GitLab webview editor cannot be opened on ${input.javaClass.name}.")
    }
    setSite(site)
    setInput(input)
    // Design §7.3: the fallback title, until a resolution justifies a better one.
    partName = input.name
  }

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

    // The sink design §7.3's rename happens through.
    val created = WebviewBrowserHost(parent, coordinator) { partName = it }
    host = created
    reload()
  }

  /**
   * Design §8.1: how [WebviewEditorOpener] re-resolves a tab that is already open. Before
   * [createPartControl] there is no host and this does nothing; the first resolution is the one
   * [createPartControl] starts.
   */
  override fun reload() {
    val key = webviewInput.key
    host?.load(key.webviewId, key.queryParams)
  }

  override val displayedSession get() = host?.displayedSession

  override fun setFocus() {
    host?.setFocus()
  }

  override fun dispose() {
    host?.dispose()
    super.dispose()
  }

  override fun isDirty(): Boolean = false

  override fun isSaveAsAllowed(): Boolean = false

  override fun doSave(monitor: IProgressMonitor?) = Unit

  override fun doSaveAs() = Unit

  /** [init] refuses every other input, so this is the one the workbench holds. */
  private val webviewInput get() = editorInput as WebviewEditorInput

  companion object {
    /** Matches the `id` of this plugin's `org.eclipse.ui.editors` contribution. */
    const val EDITOR_ID = "com.gitlab.eclipse.views.webview.WebviewEditorPart"
  }
}
