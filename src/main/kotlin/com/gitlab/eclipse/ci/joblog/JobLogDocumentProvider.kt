package com.gitlab.eclipse.ci.joblog

import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
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
 *
 * One instance serves every job-log editor ([SHARED]): the provider keeps one document per input,
 * and inputs are equal by key, so duplicate tabs of a job log share its document and a live append
 * changes it once. [getStatus] tells the editor which page to show ([JobLogContent.page]).
 */
class JobLogDocumentProvider : StorageDocumentProvider() {
  companion object {
    val SHARED = JobLogDocumentProvider()

    /** Symbolic name of this bundle, the plugin of the page statuses. */
    const val PLUGIN_ID = "com.gitlab.eclipse.gitlab-eclipse-plugin"
    const val LOADING_CODE = 1
    const val WAITING_CODE = 2
    const val UNAVAILABLE_CODE = 3
    const val ERASED_CODE = 4

    /** OK for the log; an INFO status with the page's code for every other page. */
    fun statusOf(page: JobLogPage): IStatus {
      val code = when (page) {
        JobLogPage.Log -> return Status.OK_STATUS
        JobLogPage.Loading -> LOADING_CODE
        is JobLogPage.Waiting -> WAITING_CODE
        is JobLogPage.Unavailable -> UNAVAILABLE_CODE
        JobLogPage.Erased -> ERASED_CODE
      }
      return Status(IStatus.INFO, PLUGIN_ID, code, "", null)
    }

    /** True for a status made by [statusOf] for a page other than the log. */
    fun isPageStatus(status: IStatus?): Boolean =
      status != null && status.plugin == PLUGIN_ID && status.code in LOADING_CODE..ERASED_CODE
  }

  override fun canSaveDocument(element: Any?): Boolean = false

  override fun mustSaveDocument(element: Any?): Boolean = false

  /**
   * [StorageDocumentProvider] returns no model, and without one the ProjectionViewer never gets
   * its projection model, so nothing could fold (review C-1).
   */
  override fun createAnnotationModel(element: Any?): IAnnotationModel = AnnotationModel()

  override fun getStatus(element: Any?): IStatus =
    (element as? JobLogEditorInput)?.content?.page?.let(::statusOf) ?: super.getStatus(element)

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
