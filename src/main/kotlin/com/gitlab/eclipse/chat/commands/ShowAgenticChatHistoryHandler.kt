package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.openDuoChatWindowWithAgenticView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Shows the agentic chat conversation history. Design §8.2.
 *
 * `"history"` is the `switchView` payload value the Language Server expects, and it reaches the
 * payload unchanged. This handler applies no condition of its own; the gating is downstream
 * (design §7.4).
 *
 * The call below needs the UI thread: reading the active page and `showView` inside
 * `openDuoChatWindowWithAgenticView`, and the threading contract
 * [com.gitlab.eclipse.views.LanguageServerBrowserView] documents for `requestAgenticView`. It gets
 * it because Eclipse dispatches commands on the UI thread. **Nothing in this class or its tests
 * asserts that.**
 */
class ShowAgenticChatHistoryHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    openDuoChatWindowWithAgenticView("history")
  }
}
