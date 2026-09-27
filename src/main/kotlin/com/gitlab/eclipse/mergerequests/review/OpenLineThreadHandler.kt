package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.source.IVerticalRulerInfo
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.texteditor.ITextEditor

/**
 * "Open Merge Request Thread" (design §9.2 entry (a), §11.1, FR-5), contributed to every text
 * editor's ruler menu. Always visible and enabled; the line is checked when it runs.
 *
 * UI thread: G1 (the active part is an [ITextEditor]) → the ruler's last-clicked line (E2, 0-based,
 * `-1` outside → refused) + 1 → a thread annotation on that line
 * ([ReviewSessionRegistry.threadIdsAt] not empty, the ruler left-click's check) → the popup
 * ([MrThreadPopups.openThreads]). A refusal is one notification and nothing else; once the bundle
 * is deactivated the command does nothing.
 */
@Suppress("unused")
class OpenLineThreadHandler : AbstractHandler() {
  private val logger = logger<OpenLineThreadHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    val editor = HandlerUtil.getActivePart(event) as? ITextEditor
    if (editor == null) {
      NotificationUtils.showOnUiThread(LineSnapshotCapture.NOT_TEXT_EDITOR_MESSAGE)
      return null
    }
    val zeroBasedLine = editor.getAdapter(IVerticalRulerInfo::class.java)?.lineOfLastMouseButtonActivity ?: -1
    val oneBasedLine = LineSnapshotCapture.oneBasedLineOf(zeroBasedLine)
    when {
      !DiscussionGenerationRegistry.active -> Unit
      oneBasedLine == null -> NotificationUtils.showOnUiThread(LineSnapshotCapture.NO_LINE_MESSAGE)
      ReviewSessionRegistry.threadIdsAt(editor, oneBasedLine).isEmpty() ->
        NotificationUtils.showOnUiThread(MrThreadPopups.NO_THREAD_MESSAGE)
      else -> {
        logger.info("openLineThread requested.")
        MrThreadPopups.openThreads(editor, oneBasedLine)
      }
    }
    return null
  }
}
