package com.gitlab.eclipse.ci.joblog

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

/** "Scroll to Bottom" on the job-log editor: reveals the end of the log. UI thread (command handler). */
@Suppress("unused")
class ScrollJobLogToBottomHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    (HandlerUtil.getActiveEditor(event) as? JobLogEditor)?.scrollToBottom()
    return null
  }
}
