package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.resource.ColorDescriptor
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.resource.LocalResourceManager
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextPresentationListener
import org.eclipse.jface.text.IUndoManager
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.TextPresentation
import org.eclipse.jface.text.reconciler.IReconciler
import org.eclipse.jface.text.source.Annotation
import org.eclipse.jface.text.source.ISourceViewer
import org.eclipse.jface.text.source.IVerticalRuler
import org.eclipse.jface.text.source.projection.ProjectionAnnotation
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel
import org.eclipse.jface.text.source.projection.ProjectionSupport
import org.eclipse.jface.text.source.projection.ProjectionViewer
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.RGB
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.editors.text.TextEditor
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration

private const val CHANNEL = 0xFF
private const val RED_BITS = 16
private const val GREEN_BITS = 8

/**
 * Read-only job-log editor (design §5.3): a [ProjectionViewer] folds the GitLab sections and a
 * [JobLogPresenter] colors only what is on screen. The document of a key is shared by all its
 * editors ([JobLogDocumentProvider.SHARED]); [decorations] puts the matching folds and style
 * runs on top, and live output is appended through [JobLogUiApplier]. The editor follows the
 * job's session ([JobLogEditorSessionLink]), which also picks the page: a non-log page is a status
 * of the provider, shown through [isErrorStatus]. UI thread only.
 */
class JobLogEditor : TextEditor() {
  companion object {
    const val ID = "com.gitlab.eclipse.editors.JobLog"
  }

  private var projectionSupport: ProjectionSupport? = null
  private var presenter: JobLogPresenter? = null

  /** Puts the folds and style runs on the viewer ([JobLogDecorations]). */
  internal val decorations = JobLogDecorations(this)

  private val sessionLink = JobLogEditorSessionLink(this)

  /** The loading, waiting, unavailable and erased pages ([JobLogStatusPage]). */
  internal val statusPages = JobLogStatusPages(this)

  val jobLogInput: JobLogEditorInput?
    get() = editorInput as? JobLogEditorInput

  internal val viewer: ProjectionViewer?
    get() = sourceViewer as? ProjectionViewer

  internal val jobLogPresenter: JobLogPresenter?
    get() = presenter

  override fun initializeEditor() {
    super.initializeEditor()
    setSourceViewerConfiguration(JobLogSourceViewerConfiguration(preferenceStore))
    setDocumentProvider(JobLogDocumentProvider.SHARED)
  }

  // Same steps as AbstractDecoratedTextEditor.createSourceViewer, with a ProjectionViewer.
  override fun createSourceViewer(parent: Composite, ruler: IVerticalRuler, styles: Int): ISourceViewer {
    fAnnotationAccess = annotationAccess
    fOverviewRuler = createOverviewRuler(sharedColors)
    val viewer = ProjectionViewer(parent, ruler, overviewRuler, isOverviewRulerVisible, styles)
    getSourceViewerDecorationSupport(viewer)
    return viewer
  }

  override fun createPartControl(parent: Composite) {
    super.createPartControl(parent)
    val viewer = sourceViewer as? ProjectionViewer ?: return
    projectionSupport = ProjectionSupport(viewer, annotationAccess, sharedColors).also { it.install() }
    viewer.doOperation(ProjectionViewer.TOGGLE)
    presenter = JobLogPresenter(viewer, ::isDarkTheme).also { it.install() }
    // The shared document already holds any log rendered so far; the session puts its colors and
    // folds on this viewer when it attaches it.
    sessionLink.connect()
  }

  // The loading, waiting, unavailable and erased pages are INFO statuses, not errors.
  override fun isErrorStatus(status: IStatus?): Boolean =
    JobLogDocumentProvider.isPageStatus(status) || super.isErrorStatus(status)

  // A page status gets the job's page, made from the content's page at this moment (B1 N-3).
  override fun createStatusControl(parent: Composite, status: IStatus): Control {
    val page = jobLogInput?.content?.page
    if (JobLogDocumentProvider.isPageStatus(status) && page != null) {
      statusPages.create(parent, page)?.let { return it }
    }
    return super.createStatusControl(parent, status)
  }

  // StatusTextEditor rebuilds the status control on every activation (sanityCheckState): keep the
  // page on screen while its kind is unchanged, so a running write's disabled button and a first
  // click survive (B2 review I-1). Another kind, or the log, goes through the platform as before.
  override fun updatePartControl(input: IEditorInput?) {
    if (statusPages.keepsShowing((input as? JobLogEditorInput)?.content?.page)) return
    // The platform disposes the status control now: the page it held is no longer ours (R2-4).
    statusPages.forget()
    super.updatePartControl(input)
  }

  override fun isDirty(): Boolean = false

  override fun isSaveAsAllowed(): Boolean = false

  override fun isEditable(): Boolean = false

  override fun dispose() {
    sessionLink.disconnect()
    statusPages.forget()
    projectionSupport?.dispose()
    projectionSupport = null
    presenter = null
    decorations.reset()
    super.dispose()
  }
}

/**
 * The folds and style runs of one [JobLogEditor]. Before the control exists this does nothing
 * (the session decorates the viewer when it attaches it). Once the control exists, a missing
 * document or projection model is a wiring fault (review C-1, M-1): it is audited once per editor
 * as `foldingUnavailable`, and the colors still apply. UI thread only.
 */
internal class JobLogDecorations(private val editor: JobLogEditor) {
  private var applied: RenderResult? = null
  private val foldingUnavailable = OneTimeAudit { logger<JobLogEditor>() }

  /** Replaces every fold annotation and forgets what was colored. The same result twice is applied once. */
  fun applyRendered(result: RenderResult) {
    if (result === applied) return
    if (apply(result.runs, result.folds, result.text.length)) applied = result
  }

  /**
   * [runs] and [folds] of a document of [expectedLength] chars, without its text (a duplicate tab
   * of a large log need not copy it, B2 review M-2). False before the control exists.
   */
  fun apply(runs: StyleRunIndex, folds: List<FoldRegion>, expectedLength: Int): Boolean {
    val viewer = editor.viewer
    val document = viewer?.document
    if (viewer == null || document == null) {
      if (viewer != null) reportFoldingUnavailable()
      return false
    }
    // A failed reset leaves other text in the document: show it plain rather than misplaced.
    val matches = document.length == expectedLength
    val model = viewer.projectionAnnotationModel
    if (model == null) {
      reportFoldingUnavailable()
    } else {
      replaceFolds(model, document, if (matches) folds else emptyList())
    }
    editor.jobLogPresenter?.setRuns(if (matches) runs else StyleRunIndex.EMPTY)
    return true
  }

  fun reset() {
    applied = null
  }

  private fun reportFoldingUnavailable() {
    val key = editor.jobLogInput?.key ?: return
    foldingUnavailable.error { jobLogAuditMessage("displayJobLog", key.projectId, key.jobId, "foldingUnavailable") }
  }
}

/** UI thread. Moves the caret to the end of the log and reveals it. */
internal fun JobLogEditor.scrollToBottom() {
  val document = viewer?.document ?: return
  selectAndReveal(document.length, 0)
}

private fun replaceFolds(model: ProjectionAnnotationModel, document: IDocument, folds: List<FoldRegion>) {
  val existing = model.annotationIterator.asSequence().toList().toTypedArray()
  val additions = HashMap<Annotation, Position>()
  for (fold in folds) {
    val position = foldPosition(document, fold) ?: continue
    additions[ProjectionAnnotation(fold.collapsedInitially)] = position
  }
  model.modifyAnnotations(existing, additions, null)
}

/** Adds the annotations of [folds] and leaves the existing ones, open or closed as the user left them. */
internal fun addFoldAnnotations(model: ProjectionAnnotationModel, document: IDocument, folds: List<FoldRegion>) {
  if (folds.isEmpty()) return
  val additions = HashMap<Annotation, Position>()
  for (fold in folds) {
    val position = foldPosition(document, fold) ?: continue
    additions[ProjectionAnnotation(fold.collapsedInitially)] = position
  }
  if (additions.isNotEmpty()) model.modifyAnnotations(null, additions, null)
}

/** From the start of [FoldRegion.startLine] to the end of [FoldRegion.endLine], its delimiter included. */
internal fun foldPosition(document: IDocument, fold: FoldRegion): Position? {
  if (fold.startLine < 0 || fold.endLine <= fold.startLine || fold.endLine >= document.numberOfLines) return null
  val start = document.getLineOffset(fold.startLine)
  val end = document.getLineOffset(fold.endLine) + document.getLineLength(fold.endLine)
  return Position(start, end - start)
}

/**
 * Logs one line the first time [error] is called and ignores later calls: a fault that repeats on
 * every reload should not flood the Error Log. Only a message, never a throwable. UI thread only.
 */
internal class OneTimeAudit(private val log: () -> ILog) {
  private var reported = false

  fun error(message: () -> String) {
    if (reported) return
    reported = true
    log().error(message())
  }
}

/** No undo history and no spelling reconciler: the text never changes by hand (design B4). */
internal class JobLogSourceViewerConfiguration(store: IPreferenceStore) : TextSourceViewerConfiguration(store) {
  override fun getUndoManager(sourceViewer: ISourceViewer?): IUndoManager? = null

  override fun getReconciler(sourceViewer: ISourceViewer?): IReconciler? = null
}

/**
 * Lazy coloring (design §5.3, R2-1). Each presentation colors only the part of its extent that is
 * on screen (plus one screen of lines above and below, folded text left out: [visibleRanges]) and
 * records it in [colored]; scrolling or resizing schedules one deferred pass that invalidates just
 * the visible parts not colored yet. Nothing is invalidated from inside [applyTextPresentation],
 * and coloring never changes line heights (no font, metrics or rise), so a viewport change settles
 * after one round. The colors belong to a [LocalResourceManager] tied to the text widget and are
 * freed with it. UI thread only.
 */
internal class JobLogPresenter(
  private val viewer: ProjectionViewer,
  private val isDark: () -> Boolean,
) : ITextPresentationListener {
  private val widget = viewer.textWidget
  private val resources = LocalResourceManager(JFaceResources.getResources(), widget)
  private val colors = HashMap<Int, Color>()
  private val budget = ColorBudget()
  private val colored = ColoredRegionSet()
  private var runs = StyleRunIndex.EMPTY
  private var refreshPending = false

  fun install() {
    viewer.addTextPresentationListener(this)
    viewer.addViewportListener { scheduleRefresh() }
    widget.addListener(SWT.Resize) { scheduleRefresh() }
  }

  /** Switches to [newRuns] and colors the visible part again right away. Not for use inside a presentation. */
  fun setRuns(newRuns: StyleRunIndex) {
    runs = newRuns
    colored.clear()
    refreshVisible()
  }

  /**
   * Switches to [newRuns] and forgets what was colored from [from] on (an append replaces the text
   * there). Invalidates nothing: the replace that follows repaints its own range.
   */
  fun swapRuns(newRuns: StyleRunIndex, from: Int) {
    runs = newRuns
    colored.subtract(from, Int.MAX_VALUE - from)
  }

  override fun applyTextPresentation(presentation: TextPresentation) {
    val extent = presentation.extent ?: return
    val documentLength = viewer.document?.length ?: return
    val extentEnd = extent.offset + extent.length
    if (extent.offset <= 0 && extentEnd >= documentLength) colored.clear()
    colored.subtract(extent.offset, extent.length)
    val ranges = ArrayList<StyleRange>()
    for (visible in visibleRanges()) {
      val from = maxOf(extent.offset, visible.first)
      val to = minOf(extentEnd, visible.last + 1)
      if (to <= from) continue
      ranges += styleRanges(from, to - from)
      colored.add(from, to - from)
    }
    if (ranges.isNotEmpty()) presentation.mergeStyleRanges(ranges.toTypedArray())
  }

  private fun scheduleRefresh() {
    if (refreshPending || widget.isDisposed) return
    refreshPending = true
    try {
      widget.display.asyncExec {
        refreshPending = false
        refreshVisible()
      }
    } catch (ignored: SWTException) {
      refreshPending = false
    }
  }

  /** Invalidates the visible parts not colored yet. */
  fun refreshVisible() {
    if (widget.isDisposed) return
    for (visible in visibleRanges()) {
      colored.missingIn(visible.first, visible.last + 1 - visible.first).forEach {
        viewer.invalidateTextPresentation(it.first, it.last + 1 - it.first)
      }
    }
  }

  /**
   * Model ranges of the widget lines on screen and one screen of lines above and below (review
   * I-1). Taken line by line through the projection, so the body of a collapsed section is in none
   * of them and the work stays proportional to the screen.
   */
  private fun visibleRanges(): List<IntRange> {
    val document = viewer.document ?: return emptyList()
    val bottom = widget.getLineIndex((widget.clientArea.height - 1).coerceAtLeast(0))
    val lines = visibleModelLines(widget.topIndex, bottom, widget.lineCount) { widgetLine ->
      val modelOffset = viewer.widgetOffset2ModelOffset(widget.getOffsetAtLine(widgetLine))
      if (modelOffset < 0) -1 else modelLineOf(document, modelOffset)
    }
    return modelLineOffsets(document, lines)
  }

  private fun styleRanges(offset: Int, length: Int): Array<StyleRange> {
    val found = runs.runsIn(offset, length)
    if (found.isEmpty()) return emptyArray()
    val dark = isDark()
    val defaultFg = toInt(widget.foreground.rgb)
    val defaultBg = toInt(widget.background.rgb)
    return Array(found.size) { i ->
      val run = found[i]
      val style = resolveStyle(run.style, dark, defaultFg, defaultBg)
      StyleRange(run.start, run.length, style.fgRgb?.let(::color), style.bgRgb?.let(::color)).apply {
        fontStyle = (if (style.bold) SWT.BOLD else SWT.NORMAL) or (if (style.italic) SWT.ITALIC else SWT.NORMAL)
        underline = style.underline
        strikeout = style.strikeout
      }
    }
  }

  // At most MAX_EDITOR_COLORS distinct values plus the 240 fallbacks ([ColorBudget]); none is
  // disposed before the widget.
  private fun color(rgb: Int): Color =
    budget.map(rgb).let { used ->
      colors.getOrPut(used) {
        val value = RGB((used shr RED_BITS) and CHANNEL, (used shr GREEN_BITS) and CHANNEL, used and CHANNEL)
        resources.create(ColorDescriptor.createFrom(value))
      }
    }

  private fun toInt(rgb: RGB): Int = (rgb.red shl RED_BITS) or (rgb.green shl GREEN_BITS) or rgb.blue
}

private fun modelLineOf(document: IDocument, offset: Int): Int =
  try {
    document.getLineOfOffset(offset)
  } catch (ignored: BadLocationException) {
    -1
  }

/** The 16-color palette follows the workbench theme; off the workbench (shutdown) it is light. */
private fun isDarkTheme(): Boolean =
  PlatformUI.isWorkbenchRunning() &&
    with(ThemeUtils) { PlatformUI.getWorkbench().themeManager.currentTheme.isDarkTheme() }
