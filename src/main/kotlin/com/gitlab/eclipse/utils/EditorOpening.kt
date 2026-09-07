package com.gitlab.eclipse.utils

import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.resources.IFile
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.ide.IDE

/**
 * Opens a file in the active workbench page, workspace resource first, file store otherwise.
 *
 * **UI thread only** — both `IDE` calls are workbench calls.
 *
 * The two routes are not interchangeable: an editor opened on an [IFile] connects its document
 * under the `IPath`/`LocationKind.IFILE` buffer key, one opened on an [IFileStore] under the
 * store-keyed map, and the two maps never consult each other (see
 * `com.gitlab.eclipse.lsp.edit.EditTarget`). Two callers that classify the same file differently
 * therefore end up on two buffers over it.
 *
 * **The lookup is the caller's, deliberately.** [workspaceFile] is a function, not a value, so
 * each caller supplies the workspace lookup its own siblings use *and* so the page is checked
 * before the lookup runs — the order the merge-request caller had before this was factored out.
 * `$/gitlab/openFile` passes `findFilesForLocationURI`, which is what `workspace/applyEdit` uses;
 * the merge-request handler keeps `getFileForLocation`, which is what it always used.
 *
 * Nothing here is caught: a `PartInitException` from either open, or anything the two lookups
 * throw, is the caller's to handle, and the callers differ in what they do about it.
 *
 * @param workspaceFile the workspace resource for the file, or `null` when it maps to none
 * @param fileStore the file store to fall back to, evaluated only when [workspaceFile] gives none
 * @param activePage the page to open in; defaults to the active window's active page
 * @return `false` when there was no page, or nothing to open in it — no editor was opened
 */
internal fun openInActiveEditor(
  workspaceFile: () -> IFile?,
  fileStore: () -> IFileStore?,
  activePage: () -> IWorkbenchPage? = { PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage },
): Boolean {
  val page = activePage() ?: return false
  workspaceFile()?.let {
    IDE.openEditor(page, it)
    return true
  }
  val store = fileStore() ?: return false
  IDE.openEditorOnFileStore(page, store)
  return true
}
