package com.gitlab.eclipse.clone

import org.eclipse.jface.dialogs.IInputValidator
import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.window.Window
import org.eclipse.swt.widgets.DirectoryDialog
import org.eclipse.swt.widgets.Shell
import java.io.File

/**
 * Asks the user where to clone: a parent directory ([DirectoryDialog]) followed by a new folder
 * name ([InputDialog]) — the two-step shape the design fixes as U1.
 *
 * Must be called on the UI thread: both dialogs are SWT/JFace and block until dismissed.
 *
 * Cancelling either dialog returns null and has zero side effects. The returned destination is
 * deliberately NOT created here (C26) — JGit creates it when, and only when, the clone actually
 * starts, so a cancelled flow leaves the filesystem untouched. Choosing the workspace root as
 * the parent is not recommended but not forbidden: the import succeeds there exactly when the
 * folder name equals the project name.
 */
class CloneDestinationPrompt {

  /** Returns `parent/folderName` without creating it, or null when either dialog is cancelled. */
  fun prompt(shell: Shell, suggestedFolderName: String): File? {
    val parent = promptParentDirectory(shell) ?: return null
    val folderName = promptFolderName(shell, suggestedFolderName) ?: return null
    return File(parent, folderName)
  }

  private fun promptParentDirectory(shell: Shell): File? {
    val dialog = DirectoryDialog(shell)
    dialog.text = "Clone GitLab Wiki"
    dialog.message = "Select the parent directory. The clone goes into a new folder under it."
    val path = dialog.open() ?: return null
    return File(path)
  }

  private fun promptFolderName(shell: Shell, suggestedFolderName: String): String? {
    val dialog = InputDialog(
      shell,
      "Clone GitLab Wiki",
      "New folder name (created under the selected directory when the clone starts):",
      suggestedFolderName,
      IInputValidator(::validateFolderName),
    )
    return if (dialog.open() == Window.OK) dialog.value.trim() else null
  }

  /**
   * Keeps the "parent + new folder name" contract honest: a blank name would silently target the
   * parent itself, and a separator would nest the clone somewhere the user did not pick.
   */
  private fun validateFolderName(name: String): String? =
    when {
      name.isBlank() -> "Enter a folder name."
      name.contains('/') || name.contains('\\') -> "The folder name cannot contain path separators."
      else -> null
    }
}
