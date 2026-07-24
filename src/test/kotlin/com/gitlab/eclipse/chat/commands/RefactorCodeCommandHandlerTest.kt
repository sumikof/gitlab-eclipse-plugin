package com.gitlab.eclipse.chat.commands

class RefactorCodeCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "refactorCode",
  createCommandHandler = { platformUtils -> RefactorCodeCommandHandler(platformUtils) }
)
