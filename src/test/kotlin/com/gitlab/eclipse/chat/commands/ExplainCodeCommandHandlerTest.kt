package com.gitlab.eclipse.chat.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class ExplainCodeCommandHandlerTest : ChatCommandHandlerTest(
  commandUnderTest = "/explain",
  createCommandHandler = { languageServerWrapper, textEditorProvider ->
    ExplainCodeCommandHandler(TestScope(UnconfinedTestDispatcher()), languageServerWrapper, textEditorProvider)
  }
)
