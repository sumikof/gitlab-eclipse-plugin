package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.sidebar.ChangedFileNode
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.viewers.ISelection
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.ui.handlers.HandlerUtil

/**
 * "Open Diff" on a changed file in the GitLab sidebar (REVIEW-01): compares the file between the
 * MR version's base and head in a read-only Compare editor. UI thread; the loading itself happens
 * in a background Job ([MrFileDiffEntryPoint]).
 */
class OpenMrFileDiffHandler(
  private val open: (ChangedFileNode) -> Unit = MrFileDiffEntryPoint::open,
  private val show: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    handle(HandlerUtil.getActiveMenuSelection(event) ?: HandlerUtil.getCurrentSelection(event))
    return null
  }

  internal fun handle(selection: ISelection?) {
    val node = changedFileIn(selection)
    if (node == null) show(SELECT_FILE_MESSAGE) else open(node)
  }

  internal companion object {
    const val SELECT_FILE_MESSAGE = "Select a changed file in the GitLab sidebar first."
  }
}

/** The first selected [ChangedFileNode], if the selection starts with one. */
internal fun changedFileIn(selection: ISelection?): ChangedFileNode? =
  (selection as? IStructuredSelection)?.firstElement as? ChangedFileNode
