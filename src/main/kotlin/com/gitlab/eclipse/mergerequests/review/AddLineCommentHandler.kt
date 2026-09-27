package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.texteditor.ITextEditor

/**
 * "Add Merge Request Comment on This Line…" (design §9.3 [UI turn 1], §11.1, FR-2, FR-7, FR-9),
 * contributed to the GitLab submenu of every text editor's context menu and to every text
 * editor's ruler menu. Always visible and enabled: permission is decided when it runs (FR-9).
 *
 * UI thread, one turn: G1 (the active part is an [ITextEditor]) → [LineSnapshotCapture] (G2 local
 * file, G3 not dirty, the line, the charset, the frozen [LineSnapshot]) → the "new thread" popup
 * ([MrThreadPopups.openNewThread]). A refusal is one notification and nothing else. Everything
 * after the popup (G5–G9, the send, establishing the session when the file has none) belongs to
 * the popup and the attempt.
 *
 * Which menu invoked it is the command parameter [SOURCE_PARAMETER]: the ruler contribution passes
 * [RULER_SOURCE] (the clicked line, E2); anything else — the text menu, or the command run from
 * elsewhere — uses the caret line.
 */
@Suppress("unused")
class AddLineCommentHandler : AbstractHandler() {
  private val logger = logger<AddLineCommentHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    val editor = HandlerUtil.getActivePart(event) as? ITextEditor
    if (editor == null) {
      NotificationUtils.showOnUiThread(LineSnapshotCapture.NOT_TEXT_EDITOR_MESSAGE)
      return null
    }
    val source = if (event.getParameter(SOURCE_PARAMETER) == RULER_SOURCE) LineSource.RULER else LineSource.CARET
    when (val result = LineSnapshotCapture.capture(LineSnapshotCapture.readFacts(editor, source))) {
      is CaptureResult.Refused -> NotificationUtils.showOnUiThread(result.message)
      is CaptureResult.Captured -> {
        logger.info("addLineComment requested: source=$source")
        MrThreadPopups.openNewThread(editor, result.snapshot)
      }
    }
    return null
  }

  companion object {
    const val SOURCE_PARAMETER = "gitlab-eclipse-plugin.addMergeRequestLineComment.source"
    const val RULER_SOURCE = "ruler"
  }
}
