package com.gitlab.eclipse.chat.commands

class GenerateTestsCommandHandlerTest : ChatCommandHandlerTest(
  commandUnderTest = "/tests",
  createCommandHandler = { lspClient, textEditorProvider -> GenerateTestsCommandHandler(lspClient, textEditorProvider) }
)
