package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.NOOPLspClient
import com.gitlab.eclipse.utils.TextEditorProvider

class ExplainCodeCommandHandler(
    lspClient: NOOPLspClient = NOOPLspClient(),
    textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(command = "/explain", lspClient = lspClient, textEditorProvider = textEditorProvider)