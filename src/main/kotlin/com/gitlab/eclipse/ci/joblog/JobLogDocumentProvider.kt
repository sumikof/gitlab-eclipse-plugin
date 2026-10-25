package com.gitlab.eclipse.ci.joblog

import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.source.AnnotationModel
import org.eclipse.jface.text.source.IAnnotationModel
import org.eclipse.ui.editors.text.StorageDocumentProvider

/**
 * Document provider of the job-log editor. The log is read-only and is never saved, so a
 * document that changed (a reload replaces its text) must never count as savable: that keeps the
 * tab free of the dirty marker and closing it free of the save prompt (design §5.3, AC-B1-5).
 * [mustSaveDocument] reads the same "changed" flag directly, so it is pinned too.
 *
 * The rendered text is already a `String`, so for a [JobLogEditorInput] the document takes it
 * as is: no round trip through the UTF-8 bytes of [JobLogStorage], and a reload sets the open
 * document without building a second one (review I-2).
 */
class JobLogDocumentProvider : StorageDocumentProvider() {
  override fun canSaveDocument(element: Any?): Boolean = false

  override fun mustSaveDocument(element: Any?): Boolean = false

  /**
   * [StorageDocumentProvider] returns no model, and without one the ProjectionViewer never gets
   * its projection model, so nothing could fold (review C-1).
   */
  override fun createAnnotationModel(element: Any?): IAnnotationModel = AnnotationModel()

  override fun createDocument(element: Any?): IDocument {
    val input = element as? JobLogEditorInput ?: return super.createDocument(element)
    return createEmptyDocument().also {
      it.set(input.content.rendered.text)
      setupDocument(element, it)
    }
  }

  // Same steps and events as AbstractDocumentProvider.doResetDocument, minus the extra document
  // it creates only to copy its text into the open one.
  override fun doResetDocument(element: Any?, monitor: IProgressMonitor?) {
    val input = element as? JobLogEditorInput
    val info = getElementInfo(element)
    if (input == null || info == null) {
      super.doResetDocument(element, monitor)
      return
    }
    info.fStatus = null
    fireElementContentAboutToBeReplaced(element)
    info.fDocument.set(input.content.rendered.text)
    if (info.fCanBeSaved) {
      info.fCanBeSaved = false
      addUnchangedElementListeners(element, info)
    }
    fireElementContentReplaced(element)
    fireElementDirtyStateChanged(element, false)
  }
}
