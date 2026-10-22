package com.gitlab.eclipse.currentbranch

import org.eclipse.core.resources.IFile
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor
import java.io.File

/**
 * The window-scoped editor file (status-bar design §4.1 R16/R17): the same extraction as
 * `RepositoryContextResolver.activeEditorFile()` (`PlatformUtils.getActiveTextEditor` → the input's [IFile]
 * location), but from THIS window's active page instead of the workbench's active window — so window B never
 * resolves window A's repository. The text editor is found through `getAdapter` first, then a direct cast (a
 * multi-page editor adapts to its source page). UI thread only. Total: a workbench failure (window closing)
 * reads as no file.
 */
object WindowEditorFileReader {
  fun activeEditorFile(window: IWorkbenchWindow): File? = read { window.activePage?.activeEditor }

  /** The file of an activated editor part (the part listener's argument). */
  fun fileOf(editor: IEditorPart?): File? = read { editor }

  private fun read(editor: () -> IEditorPart?): File? =
    try {
      val part = editor() ?: return null
      val text = part.getAdapter(ITextEditor::class.java) ?: (part as? ITextEditor)
      text?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
    } catch (@Suppress("SwallowedException") e: Exception) {
      null // nothing to resolve: the fetch then picks the repository as with no active editor
    }
}
