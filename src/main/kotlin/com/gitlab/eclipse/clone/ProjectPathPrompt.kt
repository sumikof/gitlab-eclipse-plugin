package com.gitlab.eclipse.clone

import org.eclipse.jface.dialogs.IInputValidator
import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.window.Window
import org.eclipse.swt.widgets.Shell

/**
 * Asks which GitLab project to open: one [InputDialog] taking a namespaced project path such as
 * `group/subgroup/project`.
 *
 * Deliberately NOT a search picker (U2): a picker would need a paged search endpoint, a debounce
 * and a result list before the flow could start at all, and the path is the value the lookup
 * actually consumes. A wrong path costs one "project not found" notification and no clone.
 *
 * Must be called on the UI thread: the dialog is JFace and blocks until dismissed. Cancelling
 * returns null and has zero side effects — the caller must not even create the clone job.
 */
class ProjectPathPrompt {

  /**
   * Shows the dialog under [title], which the caller owns so the whole command reads as one.
   * Returns the TRIMMED project path, or null when the dialog is cancelled.
   */
  fun prompt(shell: Shell, title: String): String? {
    val dialog = InputDialog(
      shell,
      title,
      "Project path (for example group/subgroup/project):",
      "",
      IInputValidator(::validateProjectPath),
    )
    return if (dialog.open() == Window.OK) dialog.value.trim() else null
  }
}

/**
 * Rejects the four inputs that cannot be a project path, validating the TRIMMED value because
 * that is what [ProjectPathPrompt] actually returns: a blank path would address `/projects/`, a
 * pasted URL would be percent-encoded whole and always miss, whitespace cannot occur inside a
 * GitLab path, and an edge `/` would encode an empty leading or trailing segment.
 *
 * It does NOT require a `/`: narrowing the accepted shape any further risks refusing a path
 * GitLab would have resolved, and the lookup already answers an unknown path with the explicit
 * [CloneMessages.projectNotFound] wording without starting a clone. Top-level and SWT-free on
 * purpose, so the headless spec (`ProjectPathPromptTest`) can reach it without a widget.
 */
internal fun validateProjectPath(path: String): String? {
  val trimmed = path.trim()
  return when {
    trimmed.isEmpty() -> "Enter a project path, for example group/subgroup/project."
    trimmed.contains("://") -> "Enter the project path, not a URL."
    trimmed.any { it.isWhitespace() } -> "A project path cannot contain spaces."
    trimmed.startsWith('/') || trimmed.endsWith('/') -> "A project path cannot start or end with '/'."
    else -> null
  }
}
