package com.gitlab.eclipse.chat.commands

class ExplainCodeCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "explainCode",
  createCommandHandler = { platformUtils -> ExplainCodeCommandHandler(platformUtils) }
)
