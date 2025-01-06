package com.gitlab.eclipse.chat.commands

class ExplainCodeCommandHandlerTest : ChatCommandHandlerTest(
 commandUnderTest = "/explain",
 createCommandHandler = { lspClient, textEditorProvider -> ExplainCodeCommandHandler(lspClient, textEditorProvider) }
)