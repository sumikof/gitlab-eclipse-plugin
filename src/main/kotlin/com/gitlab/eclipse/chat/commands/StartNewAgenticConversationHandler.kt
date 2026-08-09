package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.openDuoChatWindowWithAgenticView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Starts a new agentic chat conversation. Design §8.2, with `"newConversation"` in place of
 * `"history"`; [ShowAgenticChatHistoryHandler]'s notes on the payload value and on the UI thread
 * hold here unchanged.
 *
 * Distinct from [NewChatConversationCommandHandler], which starts a new conversation in the
 * *classic* webview by sending it a `newConversation` prompt.
 */
class StartNewAgenticConversationHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    openDuoChatWindowWithAgenticView("newConversation")
  }
}
