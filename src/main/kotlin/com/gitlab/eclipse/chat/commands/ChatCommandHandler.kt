package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.lsp.NOOPLspClient
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.TextEditorProvider
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextSelection

open class ChatCommandHandler(
  private val command: String,
  private val lspClient: NOOPLspClient,
  private val textEditorProvider: TextEditorProvider,
  private val currentFileContextProvider: CurrentFileContextProvider = CurrentFileContextProvider(textEditorProvider)
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    val context = currentFileContextProvider.provide() ?: return

    val request = NewPromptRequest(
      content = command,
      fileContext = context
    )

    lspClient.send(request)
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
