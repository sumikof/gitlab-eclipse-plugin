package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.NOOPLspClient
import com.gitlab.eclipse.utils.TextEditorProvider

class FixCodeCommandHandler(
  lspClient: NOOPLspClient = NOOPLspClient(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(command = "/fix", lspClient = lspClient, textEditorProvider = textEditorProvider)
