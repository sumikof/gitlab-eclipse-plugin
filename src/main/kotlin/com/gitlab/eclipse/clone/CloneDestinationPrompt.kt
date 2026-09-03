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
  fun prompt(shell: Shell, title: String, suggestedFolderName: String): File? {
    val parent = promptParentDirectory(shell, title) ?: return null
    val folderName = promptFolderName(shell, title, suggestedFolderName) ?: return null
    return File(parent, folderName)
  }

  private fun promptParentDirectory(shell: Shell, title: String): File? {
    val dialog = DirectoryDialog(shell)
    dialog.text = title
    dialog.message = "Select the parent directory. The clone goes into a new folder under it."
    val path = dialog.open() ?: return null
    return File(path)
  }

  private fun promptFolderName(shell: Shell, title: String, suggestedFolderName: String): String? {
    val dialog = InputDialog(
      shell,
      title,
      "New folder name (created under the selected directory when the clone starts):",
      suggestedFolderName,
      IInputValidator(::validateFolderName),
    )
    return if (dialog.open() == Window.OK) dialog.value.trim() else null
  }
}

/**
 * Keeps the "parent + new folder name" contract honest, validating the TRIMMED value because
 * that is what [CloneDestinationPrompt] actually consumes: a blank name would silently target
 * the parent itself, `.` and `..` would resolve to the parent or above it, and a separator
 * would nest the clone somewhere the user did not pick. Top-level and SWT-free on purpose, so
 * the headless spec (`CloneDestinationPromptTest`) can reach it without constructing a widget.
 */
internal fun validateFolderName(name: String): String? {
  val folder = name.trim()
  return when {
    folder.isEmpty() -> "Enter a folder name."
    folder == "." || folder == ".." -> "The folder name cannot be '.' or '..'."
    folder.contains('/') || folder.contains('\\') -> "The folder name cannot contain path separators."
    else -> null
  }
}
