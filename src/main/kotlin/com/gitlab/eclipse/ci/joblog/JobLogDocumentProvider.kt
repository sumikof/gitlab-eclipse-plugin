package com.gitlab.eclipse.ci.joblog

import org.eclipse.ui.editors.text.StorageDocumentProvider

/**
 * Document provider of the job-log editor. The log is read-only and is never saved, so a
 * document that changed (a reload replaces its text) must never count as savable: that keeps the
 * tab free of the dirty marker and closing it free of the save prompt (design §5.3, AC-B1-5).
 * [mustSaveDocument] reads the same "changed" flag directly, so it is pinned too.
 */
class JobLogDocumentProvider : StorageDocumentProvider() {
  override fun canSaveDocument(element: Any?): Boolean = false

  override fun mustSaveDocument(element: Any?): Boolean = false
}
