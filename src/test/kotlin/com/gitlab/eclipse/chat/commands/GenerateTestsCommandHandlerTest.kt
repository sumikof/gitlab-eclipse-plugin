package com.gitlab.eclipse.chat.commands

class GenerateTestsCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "generateTests",
  createCommandHandler = { platformUtils -> GenerateTestsCommandHandler(platformUtils) }
)
