package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.resource.ColorDescriptor
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.resource.LocalResourceManager
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
import org.eclipse.jface.text.source.projection.ProjectionSupport
import org.eclipse.jface.text.source.projection.ProjectionViewer
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.RGB
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.editors.text.TextEditor
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration

private const val CHANNEL = 0xFF
private const val RED_BITS = 16
private const val GREEN_BITS = 8

/**
 * Read-only job-log editor (design §5.3): a [ProjectionViewer] folds the GitLab sections and a
 * [JobLogPresenter] colors only what is on screen. The document comes from the shared
 * [JobLogContent] through [JobLogDocumentProvider]; [applyRendered] puts the matching folds and
 * style runs on top. UI thread only.
 */
class JobLogEditor : TextEditor() {
  companion object {
    const val ID = "com.gitlab.eclipse.editors.JobLog"
  }

  private var projectionSupport: ProjectionSupport? = null
  private var presenter: JobLogPresenter? = null
  private var applied: RenderResult? = null

  val jobLogInput: JobLogEditorInput?
    get() = editorInput as? JobLogEditorInput

  override fun initializeEditor() {
    super.initializeEditor()
    setSourceViewerConfiguration(JobLogSourceViewerConfiguration(preferenceStore))
    setDocumentProvider(JobLogDocumentProvider())
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
    // The document was just read from the shared content; a reload that came before the
    // control existed is already in it, so apply the same result now.
    jobLogInput?.content?.rendered?.let(::applyRendered)
  }

  /**
   * UI thread. Puts the folds and style runs of [result] on the document, which the caller has
   * already reset to `result.text`. Replaces every fold annotation and forgets what was colored.
   * Before the control exists this does nothing ([createPartControl] applies the content then).
   */
  fun applyRendered(result: RenderResult) {
    if (result === applied) return
    val viewer = sourceViewer as? ProjectionViewer ?: return
    val document = viewer.document ?: return
    applied = result
    // A failed reset leaves other text in the document: show it plain rather than misplaced.
    val matches = document.length == result.text.length
    replaceFolds(viewer, document, if (matches) result.folds else emptyList())
    presenter?.setRuns(if (matches) result.runs else StyleRunIndex.EMPTY)
  }

  /** UI thread. Moves the caret to the end of the log and reveals it. */
  fun scrollToBottom() {
    val document = sourceViewer?.document ?: return
    selectAndReveal(document.length, 0)
  }

  override fun isDirty(): Boolean = false

  override fun isSaveAsAllowed(): Boolean = false

  override fun isEditable(): Boolean = false

  override fun dispose() {
    projectionSupport?.dispose()
    projectionSupport = null
    presenter = null
    applied = null
    super.dispose()
  }

  private fun replaceFolds(viewer: ProjectionViewer, document: IDocument, folds: List<FoldRegion>) {
    val model = viewer.projectionAnnotationModel ?: return
    val existing = model.annotationIterator.asSequence().toList().toTypedArray()
    val additions = HashMap<Annotation, Position>()
    for (fold in folds) {
      val position = foldPosition(document, fold) ?: continue
      additions[ProjectionAnnotation(fold.collapsedInitially)] = position
    }
    model.modifyAnnotations(existing, additions, null)
  }
}

/** From the start of [FoldRegion.startLine] to the end of [FoldRegion.endLine], its delimiter included. */
private fun foldPosition(document: IDocument, fold: FoldRegion): Position? {
  if (fold.startLine < 0 || fold.endLine <= fold.startLine || fold.endLine >= document.numberOfLines) return null
  val start = document.getLineOffset(fold.startLine)
  val end = document.getLineOffset(fold.endLine) + document.getLineLength(fold.endLine)
  return Position(start, end - start)
}

/** No undo history and no spelling reconciler: the text never changes by hand (design B4). */
internal class JobLogSourceViewerConfiguration(store: IPreferenceStore) : TextSourceViewerConfiguration(store) {
  override fun getUndoManager(sourceViewer: ISourceViewer?): IUndoManager? = null

  override fun getReconciler(sourceViewer: ISourceViewer?): IReconciler? = null
}

/**
 * Lazy coloring (design §5.3, R2-1). Each presentation colors only the part of its extent that is
 * on screen (plus one screen above and below) and records it in [colored]; scrolling or resizing
 * schedules one deferred pass that invalidates just the visible parts not colored yet. Nothing is
 * invalidated from inside [applyTextPresentation], and coloring never changes line heights (no
 * font, metrics or rise), so a viewport change settles after one round. The colors belong to a
 * [LocalResourceManager] tied to the text widget and are freed with it. UI thread only.
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

  override fun applyTextPresentation(presentation: TextPresentation) {
    val extent = presentation.extent ?: return
    val documentLength = viewer.document?.length ?: return
    val extentEnd = extent.offset + extent.length
    if (extent.offset <= 0 && extentEnd >= documentLength) colored.clear()
    colored.subtract(extent.offset, extent.length)
    val visible = visibleRange(documentLength)
    val from = maxOf(extent.offset, visible.first)
    val to = minOf(extentEnd, visible.last + 1)
    if (to <= from) return
    val ranges = styleRanges(from, to - from)
    if (ranges.isNotEmpty()) presentation.mergeStyleRanges(ranges)
    colored.add(from, to - from)
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

  private fun refreshVisible() {
    if (widget.isDisposed) return
    val documentLength = viewer.document?.length ?: return
    val visible = visibleRange(documentLength)
    if (visible.isEmpty()) return
    colored.missingIn(visible.first, visible.last + 1 - visible.first).forEach {
      viewer.invalidateTextPresentation(it.first, it.last + 1 - it.first)
    }
  }

  /** The model range on screen widened by one screen on each side, clamped to the document. */
  private fun visibleRange(documentLength: Int): IntRange {
    val top = viewer.topIndexStartOffset.coerceAtLeast(0)
    val bottom = viewer.bottomIndexEndOffset.let { if (it < top) documentLength else it + 1 }
    val screen = bottom - top
    return (top - screen).coerceAtLeast(0) until (bottom + screen).coerceAtMost(documentLength)
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

/** The 16-color palette follows the workbench theme; off the workbench (shutdown) it is light. */
private fun isDarkTheme(): Boolean =
  PlatformUI.isWorkbenchRunning() &&
    with(ThemeUtils) { PlatformUI.getWorkbench().themeManager.currentTheme.isDarkTheme() }
