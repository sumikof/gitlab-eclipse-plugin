package com.gitlab.eclipse.views.inlinethread

import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPart
import org.eclipse.ui.IWorkbenchPartReference

/**
 * The page listener of an [InlineThreadPopup], SWT-free (design §9.6 / FR-11): [onGone] runs when
 * the popup's [editor] closes (`partClosed`) or keeps its part but shows another input
 * (`partInputChanged`, a reused editor — `IReusableEditor.setInput`), since the popup's frozen
 * line snapshot would otherwise post against a file the editor no longer shows. The part is matched
 * by the [reference] taken at open time first, with `getPart(false)` as the fallback.
 */
internal class EditorGoneListener(
  private val editor: IWorkbenchPart,
  private val reference: IWorkbenchPartReference?,
  private val onGone: () -> Unit,
) : IPartListener2 {
  // Every other IPartListener2 method is a default method on this platform (see ReviewEditorTracker).
  override fun partClosed(partRef: IWorkbenchPartReference) = onPartGone(partRef)

  // The part stays open but shows another input: the popup's snapshot belongs to the old one.
  override fun partInputChanged(partRef: IWorkbenchPartReference) = onPartGone(partRef)

  private fun onPartGone(partRef: IWorkbenchPartReference) {
    if (partRef === reference || partRef.getPart(false) === editor) onGone()
  }
}
