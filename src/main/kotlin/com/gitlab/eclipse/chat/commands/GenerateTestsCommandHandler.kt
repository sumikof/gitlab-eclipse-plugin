package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.NOOPLspClient
import com.gitlab.eclipse.utils.TextEditorProvider

class GenerateTestsCommandHandler(
  lspClient: NOOPLspClient = NOOPLspClient(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(command = "/tests", lspClient = lspClient, textEditorProvider = textEditorProvider)
