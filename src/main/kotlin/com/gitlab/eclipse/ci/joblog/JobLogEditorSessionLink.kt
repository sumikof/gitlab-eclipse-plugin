package com.gitlab.eclipse.ci.joblog

import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.source.projection.ProjectionViewer
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

  // Same kind: the page on screen is updated in place (time, status, button). Another kind: the
  // status control is rebuilt, so the old page is forgotten first (R2-4).
  override fun showPage(page: JobLogPage) {
    val kind = page.javaClass
    if (kind == shownKind) {
      editor.statusPages.update(page)
      return
    }
    shownKind = kind
    editor.statusPages.forget()
    editor.updatePartControl(editor.editorInput)
  }

  // Null until the control (and with it the viewer, its document and the presenter) exists.
  override val surface: JobLogTextSurface?
    get() {
      val viewer = editor.viewer ?: return null
      val presenter = editor.jobLogPresenter ?: return null
      val document = viewer.document ?: return null
      return ViewerSurface(editor, viewer, presenter, document)
    }

  override fun decorate(runs: StyleRunIndex, folds: List<FoldRegion>) {
    val document = editor.viewer?.document ?: return
    editor.decorations.apply(runs, folds, document.length)
  }

  private fun visibilityOf(partRef: IWorkbenchPartReference, now: Boolean) {
    if (partRef.getPart(false) !== editor || now == visible) return
    visible = now
    key?.let(JobLogSessionManager::visibilityChanged)
  }
}

/** The editor's viewer as [JobLogUiApplier] sees it. Made per update; holds nothing. UI thread only. */
private class ViewerSurface(
  private val editor: JobLogEditor,
  private val viewer: ProjectionViewer,
  private val presenter: JobLogPresenter,
  override val document: IDocument,
) : JobLogTextSurface {
  override var topIndex: Int
    get() = viewer.topIndex
    set(value) {
      viewer.topIndex = value
    }

  override fun isTailVisible(): Boolean = isTailVisible(viewer.bottomIndexEndOffset, document)

  override fun swapRuns(runs: StyleRunIndex, from: Int) = presenter.swapRuns(runs, from)

  override fun refreshVisible() = presenter.refreshVisible()

  override fun addFolds(folds: List<FoldRegion>) {
    // A missing model is audited by the decorations (foldingUnavailable); the colors still apply.
    val model = viewer.projectionAnnotationModel ?: return
    addFoldAnnotations(model, document, folds)
  }

  override fun applyRendered(result: RenderResult) = editor.decorations.applyRendered(result)

  // Not selectAndReveal (B1 M-8): the caret and a selection being copied stay where they are.
  override fun revealEnd() = viewer.revealRange(document.length, 0)
}
