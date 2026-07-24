package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.utils.openDuoChatWindowWithClassicPrompt
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextSelection

open class ChatCommandHandler(
  private val promptType: String,
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

    openDuoChatWindowWithClassicPrompt(payload)
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
