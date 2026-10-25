package com.gitlab.eclipse.ci.joblog

import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPartReference

/**
 * Connects one [JobLogEditor] to the session of its key (design §5.3): attached when its control
 * exists, detached on dispose, and told when the editor is shown or hidden on its page (polling
 * pauses while no editor of the key is visible). It switches the page only when the kind of page
 * changes, because `updatePartControl` rebuilds the status control every time (R2-2). UI thread only.
 */
internal class JobLogEditorSessionLink(private val editor: JobLogEditor) : JobLogSessionView {
  private var key: JobLogKey? = null
  private var workbenchPage: IWorkbenchPage? = null
  private var shownKind: Class<out JobLogPage>? = null
  private var visible = false
  private var disposed = false

  // Only this editor's own visibility; IPartListener2 methods are all default.
  private val listener = object : IPartListener2 {
    override fun partVisible(partRef: IWorkbenchPartReference) = visibilityOf(partRef, true)

    override fun partHidden(partRef: IWorkbenchPartReference) = visibilityOf(partRef, false)
  }

  override val isDisposed: Boolean
    get() = disposed

  override val isVisible: Boolean
    get() = visible

  /** End of `createPartControl`: the page the editor was opened with is already on screen. */
  fun connect() {
    val input = editor.jobLogInput ?: return
    val page = editor.site.page
    shownKind = input.content.page.javaClass
    visible = page.isPartVisible(editor)
    page.addPartListener(listener)
    workbenchPage = page
    key = input.key
    JobLogSessionManager.attach(input.key, this)
  }

  /** Start of `dispose`. The session is disposed with its last editor. */
  fun disconnect() {
    disposed = true
    workbenchPage?.removePartListener(listener)
    workbenchPage = null
    key?.let { JobLogSessionManager.detach(it, this) }
    key = null
  }

  override fun showPage(page: JobLogPage) {
    val kind = page.javaClass
    if (kind == shownKind) return
    shownKind = kind
    editor.updatePartControl(editor.editorInput)
  }

  // Minimal until the UI applier: the document is shared by the key's editors, so the first one
  // changes it and the others find it changed already.
  override fun apply(update: JobLogUpdate, followIfAtEnd: Boolean) {
    val document = editor.viewer?.document ?: return
    when (update) {
      is JobLogUpdate.Full -> {
        val text = update.result.text
        if (document.length != text.length || document.get() != text) document.set(text)
        editor.applyRendered(update.result)
      }
      is JobLogUpdate.Append -> {
        val from = update.replaceFrom
        val length = update.text.length
        val done = document.length == from + length && document.get(from, length) == update.text
        if (!done) document.replace(from, document.length - from, update.text)
        editor.jobLogPresenter?.setRuns(update.runs)
      }
    }
  }

  override fun decorate(runs: StyleRunIndex, folds: List<FoldRegion>) {
    val document = editor.viewer?.document ?: return
    editor.applyRendered(RenderResult(document.get(), runs, folds, omitted = false))
  }

  private fun visibilityOf(partRef: IWorkbenchPartReference, now: Boolean) {
    if (partRef.getPart(false) !== editor || now == visible) return
    visible = now
    key?.let(JobLogSessionManager::visibilityChanged)
  }
}
