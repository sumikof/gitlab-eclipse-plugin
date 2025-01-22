package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextSelection
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

open class ChatCommandHandler(
  private val promptType: String,
  private val coroutineScope: CoroutineScope,
  private val textEditorProvider: TextEditorProvider,
  private val currentFileContextProvider: CurrentFileContextProvider = CurrentFileContextProvider()
) : KoinComponent, AbstractHandler() {

  private val languageServer: GitLabLanguageServer by inject()

  override fun execute(event: ExecutionEvent) {
    val textEditor = textEditorProvider.getActiveTextEditor() ?: return
    val context = currentFileContextProvider.provide(textEditor) ?: return

    val request = ExtensionToPluginNotification(
      pluginId = "duo-chat-v2",
      type = "newPrompt",
      payload = NewPromptRequest(
        prompt = promptType,
        fileContext = context
      )
    )

    coroutineScope.launch {
      languageServer.pluginNotification(request)
    }
  }

  override fun isEnabled(): Boolean {
    val selection = textEditorProvider
      .getActiveTextEditor()
      ?.selectionProvider
      ?.selection as? ITextSelection
      ?: return false

    return selection.text.isNotEmpty()
  }
}
