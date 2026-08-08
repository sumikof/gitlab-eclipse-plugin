package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PartInitException

/** What [WebviewEditorOpener] needs from a webview tab that is already open. Design §8.1. */
interface WebviewEditorSurface : IEditorPart {
  /** Design §7.2b: the session of the last `Show` that was applied, or null if none has been. */
  val displayedSession: LanguageServerSession?

  /** Asks the tab to resolve its webview again. Design §8.1. */
  fun reload()
}

/**
 * Brings up the editor-area webview for one [WebviewEditorInput]. Design §8.1.
 *
 * The scan is over the [IWorkbenchPage] it is given, and no path from here reaches the workbench's
 * other windows: design §7.3 records what the earlier all-windows scan did to a tab in a window the
 * user was not looking at. `MergedYamlEditorOpener` (`:60-63`) collects matches from every window
 * because a match there was opened for the same key but can be showing older text. A match here was
 * opened for the same [WebviewEditorKey], which names the content, so there is nothing to deliver to
 * it. A tab in another window can still hold a uri from a language server session that has ended;
 * design §8.1 leaves that to the command the user of that window runs.
 *
 * A page is taken rather than looked up so that design §21's A22 can be checked headless, and so
 * that a caller that has already consulted the active page — design §8.1's `root/flow` precondition
 * does — acts on the same one it consulted. Every call must be on the UI thread (design §15).
 */
class WebviewEditorOpener(private val languageServerWrapper: GitLabLanguageServerWrapper) {
  /**
   * Activates the tab for [input] on [page], opening one if there is none, and re-resolves it when
   * what it shows came from a language server session that is no longer the current one (design
   * §8.1). An open tab whose content is from the current session is only activated, which keeps the
   * state the webview holds on screen.
   *
   * @throws PartInitException if [page] cannot open the editor. Design §12 requires that failure to
   *   reach the Error Log and a notification, and this class has no channel for either, so the
   *   caller is responsible for it.
   */
  fun openOrReload(page: IWorkbenchPage, input: WebviewEditorInput) {
    val existing = page.findEditors(input, null, IWorkbenchPage.MATCH_INPUT)
      .firstNotNullOfOrNull { reference -> reference.getEditor(true) as? WebviewEditorSurface }

    if (existing == null) {
      page.openEditor(input, WebviewEditorPart.EDITOR_ID)
      return
    }

    page.activate(existing)

    // §7.1a's comparison: one session is one connection, and only identity tells two of them apart.
    // A tab that has applied nothing has no session to compare, and design §7.2b requires a command
    // run after a resolution that produced no content to reach a fresh resolution.
    val displayed = existing.displayedSession
    if (displayed == null || displayed !== languageServerWrapper.currentSnapshot?.session) existing.reload()
  }
}
