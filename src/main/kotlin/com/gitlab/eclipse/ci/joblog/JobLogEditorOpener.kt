package com.gitlab.eclipse.ci.joblog

import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PartInitException
import org.eclipse.ui.PlatformUI

/**
 * Opens the job-log editor and closes every one at shutdown. The log itself, its memory and the
 * editors' lifetime belong to the job's session ([JobLogSessionManager]).
 *
 * A singleton `object` (not a Koin service) because `GitLabEclipseStartup.start` must not be
 * modified (no start-time wiring): one instance per bundle classloader gives the per-activation
 * isolation the activation guard relies on. UI thread only.
 */
object JobLogEditorOpener {
  /**
   * Brings the editor for [input] to the front of the active page, or opens one there. Duplicate
   * tabs in other pages are fine: they share the session and its document.
   *
   * @throws PartInitException if the active page cannot open the editor (the session audits it
   *   and notifies).
   */
  fun show(input: JobLogEditorInput) {
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
    val existing = page.findEditor(input)
    if (existing != null) page.activate(existing) else page.openEditor(input, JobLogEditor.ID)
  }

  /**
   * Shutdown path: drops every session first (timers, requests and the log's memory, even when the
   * workbench is closing and keeps its editors), then closes every open job-log editor. UI thread
   * only (the caller marshals via syncExec).
   */
  fun disposeAtShutdown() {
    JobLogSessionManager.disposeAll()
    val workbench = PlatformUI.getWorkbench()
    // If the workbench is tearing down, editors die with it - skip closing them.
    if (workbench.isClosing) return
    workbench.workbenchWindows.forEach { window ->
      window.pages.forEach { page -> closeJobLogEditors(page) }
    }
  }

  private fun closeJobLogEditors(page: IWorkbenchPage) {
    val jobLogEditors = page.editorReferences
      .filter { safeInput(it) is JobLogEditorInput }
      .toTypedArray()
    if (jobLogEditors.isNotEmpty()) {
      page.closeEditors(jobLogEditors, false) // false = don't save (read-only)
    }
  }

  private fun safeInput(ref: IEditorReference): IEditorInput? =
    try {
      ref.editorInput
    } catch (ignored: PartInitException) {
      null
    }
}
