package com.gitlab.eclipse.chat.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class FixCodeCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "fixCode",
  createCommandHandler = { languageServerWrapper, textEditorProvider ->
    FixCodeCommandHandler(TestScope(UnconfinedTestDispatcher()), textEditorProvider)
  }
)
