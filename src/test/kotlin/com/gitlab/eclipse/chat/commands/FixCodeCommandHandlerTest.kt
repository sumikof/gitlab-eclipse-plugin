package com.gitlab.eclipse.chat.commands

class FixCodeCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "fixCode",
  createCommandHandler = { platformUtils -> FixCodeCommandHandler(platformUtils) }
)
