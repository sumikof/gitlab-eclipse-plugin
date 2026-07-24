package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.selectDuoChatWebview
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Switches the Duo Chat view to the webview id carried by the optional
 * [WEBVIEW_ID_PARAMETER] command parameter (classic `duo-chat-v2` or `agentic-duo-chat`),
 * then refreshes the view so availability is re-consulted. Without the parameter (the
 * selector pulldown button itself) it just reveals and re-resolves the view.
 */
class SelectChatWebviewCommandHandler : AbstractHandler() {
  companion object {
    const val WEBVIEW_ID_PARAMETER = "gitlab-eclipse-plugin.commands.SelectChatWebview.webviewId"
  }

  override fun execute(event: ExecutionEvent) {
    selectDuoChatWebview(event.getParameter(WEBVIEW_ID_PARAMETER))
  }
}
