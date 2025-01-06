package com.gitlab.eclipse.chat.commands

class RefactorCodeCommandHandlerTest : ChatCommandHandlerTest(
 commandUnderTest = "/refactor",
 createCommandHandler = { lspClient, textEditorProvider -> RefactorCodeCommandHandler(lspClient, textEditorProvider) }
)