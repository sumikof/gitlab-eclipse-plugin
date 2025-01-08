package com.gitlab.eclipse.chat.commands

class FixCodeCommandHandlerTest : ChatCommandHandlerTest(
  commandUnderTest = "/fix",
  createCommandHandler = { lspClient, textEditorProvider -> FixCodeCommandHandler(lspClient, textEditorProvider) }
)
