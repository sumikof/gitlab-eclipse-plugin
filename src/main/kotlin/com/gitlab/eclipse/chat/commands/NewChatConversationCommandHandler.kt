package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Starts a new Duo Chat conversation.
 *
 * Unlike [ChatCommandHandler] this command stays enabled without a selection, because
 * resetting the conversation is unrelated to what is selected.
 */
class NewChatConversationCommandHandler(
  private val coroutineScope: CoroutineScope = service(),
  private val gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient = service(),
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val currentFileContextProvider: CurrentFileContextProvider = CurrentFileContextProvider()
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    val payload = NewPromptRequest(prompt = "newConversation", fileContext = selectedFileContext())

    openDuoChatWindow()

    coroutineScope.launch {
      gitLabDuoChatWebViewClient.notify("newPrompt", payload)
    }
  }

  /**
   * CurrentFileContextProvider still returns a context when nothing is selected, and that
   * context carries the whole document in contentAboveCursor/contentBelowCursor. Dropping it
   * here keeps an empty selection from sending the entire file, matching the VS Code
   * extension, which returns no context for an empty selection.
   */
  private fun selectedFileContext(): FileContext? {
    val textEditor = platformUtils.getActiveTextEditor() ?: return null

    return currentFileContextProvider
      .provide(textEditor)
      ?.takeIf { it.selectedText.isNotEmpty() }
  }
}
