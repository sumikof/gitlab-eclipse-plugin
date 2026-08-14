package com.gitlab.eclipse.publish.handlers

import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import java.io.File

/**
 * The SWT half of [PublishToGitLabHandler], kept separate so the handler reads as the flow it is.
 *
 * Every method must run on the UI thread. None of them changes anything: publishing only becomes
 * destructive after [confirm] returns true, which is what makes A15 hold.
 */
class PublishDialogs {
  fun pickFolder(folders: List<File>): File? {
    val labels = folders.associateBy { it.name }
    return labels[pick("Select a folder to publish", labels.keys.toTypedArray())]
  }

  /** Private first: the default has to be the one that cannot expose the code. */
  fun pickVisibility(): String? {
    val labels = linkedMapOf(
      "Private — visible only to project members" to "private",
      "Public — accessible without authentication" to "public",
    )
    return labels[pick("Select privacy level", labels.keys.toTypedArray())]
  }

  /** True for SSH. */
  fun pickConnection(): Boolean? {
    val labels = linkedMapOf("HTTPS" to false, "SSH" to true)
    return labels[pick("Select the remote connection type", labels.keys.toTypedArray())]
  }

  fun promptNamespace(): String? =
    prompt("Namespace", "Group path to create the project in. Leave blank for your own namespace.")

  fun promptProjectName(): String? =
    prompt("Project name", "The project path to create.")?.takeIf { it.isNotBlank() }

  fun confirm(message: String): Boolean =
    MessageDialog.openConfirm(shell(), TITLE, message)

  fun notify(message: String) {
    MessageDialog.openInformation(shell(), "GitLab", message)
  }

  private fun pick(message: String, elements: Array<String>): String? {
    val dialog = ElementListSelectionDialog(shell(), LabelProvider())
    dialog.setTitle(TITLE)
    dialog.setMessage(message)
    dialog.setElements(elements)
    return if (dialog.open() == Window.OK) dialog.firstResult as? String else null
  }

  private fun prompt(title: String, message: String): String? {
    val dialog = InputDialog(shell(), title, message, "", null)
    return if (dialog.open() == Window.OK) dialog.value else null
  }

  private fun shell() = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell

  private companion object {
    const val TITLE = "Publish to GitLab"
  }
}
