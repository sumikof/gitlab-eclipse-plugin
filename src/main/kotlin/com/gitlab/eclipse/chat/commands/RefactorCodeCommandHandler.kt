package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.NOOPLspClient
import com.gitlab.eclipse.utils.TextEditorProvider

class RefactorCodeCommandHandler(
  lspClient: NOOPLspClient = NOOPLspClient(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(command = "/refactor", lspClient = lspClient, textEditorProvider = textEditorProvider)
