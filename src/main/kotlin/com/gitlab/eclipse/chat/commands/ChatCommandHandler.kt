package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextSelection

open class ChatCommandHandler(
  private val promptType: String,
  private val coroutineScope: CoroutineScope,
  private val gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient,
  private val platformUtils: PlatformUtils,
  private val currentFileContextProvider: CurrentFileContextProvider = CurrentFileContextProvider()
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    val textEditor = platformUtils.getActiveTextEditor() ?: return
    val context = currentFileContextProvider.provide(textEditor) ?: return

    val payload = NewPromptRequest(
      prompt = promptType,
      fileContext = context
    )

    openDuoChatWindow()

    coroutineScope.launch {
      gitLabDuoChatWebViewClient.notify("newPrompt", payload)
    }
  }

  override fun isEnabled(): Boolean {
    val selection = platformUtils
      .getActiveTextEditor()
      ?.selectionProvider
      ?.selection as? ITextSelection
      ?: return false

    return selection.text.isNotEmpty()
  }
}
