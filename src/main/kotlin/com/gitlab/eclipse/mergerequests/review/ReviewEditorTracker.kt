package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.source.IVerticalRulerInfo
import org.eclipse.swt.events.MouseAdapter
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.events.MouseListener
import org.eclipse.swt.widgets.Control
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.texteditor.ITextEditor

/**
 * The SWT listeners of [ReviewSessionRegistry]: which document each connected editor shows, a
 * part listener per workbench page that reports the editor's close (design §9.6), and a
 * best-effort left-click listener on the editor's vertical ruler (design U1). UI-thread only.
 *
 * Platform facts relied on (design §6.4):
 * - E9: `AbstractTextEditor.getAdapter(IVerticalRulerInfo)` is the editor's ruler; its `control`
 *   is the `CompositeRuler` canvas, which forwards `addMouseListener` / `removeMouseListener` to
 *   every column control (verified on `org.eclipse.jface.text` 3.25.200,
 *   `CompositeRulerCanvas.addListener(Class, EventListener)` + `childAdded`), so one listener
 *   sees clicks on the annotation column, the line numbers and the Duo column alike.
 * - E2: `getLineOfLastMouseButtonActivity()` is the 0-based document line of the last press,
 *   `-1` outside the text; it is still valid in the `mouseUp` that follows.
 * - The document an editor shows is recorded at [track] time and never re-read from the
 *   editor's document provider afterwards: by the time `partClosed` arrives the provider may
 *   already be disconnected. The closed part is matched by its [IWorkbenchPartReference] first
 *   (`IWorkbenchPage.getReference(part)` taken at track time), with `getPart(false)` as the fallback.
 *
 * @param onEditorClosed called once per released editor with the document it showed, after its
 *   ruler listener is removed; never while the editor is still tracked.
 * @param onRulerClick called on a left button release over a document line of a tracked editor,
 *   with the 1-based line of the press.
 */
internal class ReviewEditorTracker(
  private val onEditorClosed: (ITextEditor, IDocument) -> Unit,
  private val onRulerClick: (editor: ITextEditor, document: IDocument, oneBasedLine: Int) -> Unit,
) {
  private val logger by lazy { logger<ReviewEditorTracker>() }

  private class Tracked(
    val document: IDocument,
    val page: IWorkbenchPage?,
    val reference: IWorkbenchPartReference?,
    val rulerControl: Control?,
    val rulerListener: MouseListener?,
  )

  private val tracked = HashMap<ITextEditor, Tracked>()
  private val pages = HashSet<IWorkbenchPage>()

  // Every other IPartListener2 method is a default method on this platform (see JobLogEditorOpener).
  private val partListener = object : IPartListener2 {
    override fun partClosed(partRef: IWorkbenchPartReference) = onPartClosed(partRef)
  }

  /**
   * Starts tracking [editor] as showing [document]; a repeat call for the same pair is a no-op.
   * An editor tracked with another document (its input was replaced underneath the session) is
   * released first, as if it had closed.
   */
  fun track(editor: ITextEditor, document: IDocument) {
    val existing = tracked[editor]
    if (existing != null && existing.document === document) return
    if (existing != null) release(editor)
    val page: IWorkbenchPage? = editor.site?.page
    if (page != null && pages.add(page)) page.addPartListener(partListener)
    val ruler = installRulerListener(editor, document)
    tracked[editor] = Tracked(document, page, page?.getReference(editor), ruler?.first, ruler?.second)
  }

  /** The document [editor] was tracked with, or `null` when it is not tracked. */
  fun documentOf(editor: ITextEditor): IDocument? = tracked[editor]?.document

  /**
   * Stops tracking [editor]: removes its ruler listener (unless the control is already disposed),
   * drops the page listener when no tracked editor of that page remains, then reports the editor
   * through `onEditorClosed`. A no-op for an untracked editor.
   */
  fun release(editor: ITextEditor) {
    val entry = tracked.remove(editor) ?: return
    try {
      val control = entry.rulerControl
      if (control != null && entry.rulerListener != null && !control.isDisposed) {
        control.removeMouseListener(entry.rulerListener)
      }
      val page = entry.page
      if (page != null && tracked.values.none { it.page === page }) {
        pages.remove(page)
        page.removePartListener(partListener)
      }
    } catch (e: Exception) {
      // A widget or page torn down under us (shutdown): the editor is untracked regardless,
      // and the close is still reported below.
      logger.error("reviewSession listener removal failed: exceptionType=${e.javaClass.name}")
    }
    onEditorClosed(editor, entry.document)
  }

  /**
   * [release] for every tracked editor (bundle stop). Each editor is released under its own
   * guard, so one failing release (a hook, a disposed widget) never leaves the others tracked.
   */
  fun releaseAll() {
    tracked.keys.toList().forEach { editor ->
      try {
        release(editor)
      } catch (e: Exception) {
        logger.error("reviewSession editor release failed: exceptionType=${e.javaClass.name}")
      }
    }
  }

  private fun onPartClosed(partRef: IWorkbenchPartReference) {
    try {
      val editor = tracked.entries.firstOrNull { it.value.reference === partRef }?.key
        ?: (partRef.getPart(false) as? ITextEditor)?.takeIf { it in tracked }
        ?: return
      release(editor)
    } catch (e: Exception) {
      // Never let a listener failure reach the workbench event loop.
      logger.error("reviewSession editor release failed: exceptionType=${e.javaClass.name}")
    }
  }

  /** Best effort (design U1): editors without a ruler control simply get no click handling. */
  private fun installRulerListener(editor: ITextEditor, document: IDocument): Pair<Control, MouseListener>? {
    val rulerInfo = editor.getAdapter(IVerticalRulerInfo::class.java) ?: return null
    val control = rulerInfo.control?.takeUnless { it.isDisposed } ?: return null
    val listener = object : MouseAdapter() {
      override fun mouseUp(e: MouseEvent) {
        if (e.button != LEFT_BUTTON) return
        try {
          val line = rulerInfo.lineOfLastMouseButtonActivity
          if (line >= 0) onRulerClick(editor, document, line + 1)
        } catch (ex: Exception) {
          logger.error("reviewSession ruler click failed: exceptionType=${ex.javaClass.name}")
        }
      }
    }
    control.addMouseListener(listener)
    return control to listener
  }

  private companion object {
    const val LEFT_BUTTON = 1
  }
}
