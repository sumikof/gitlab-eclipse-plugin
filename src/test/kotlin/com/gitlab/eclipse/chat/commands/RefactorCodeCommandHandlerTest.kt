package com.gitlab.eclipse.chat.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class RefactorCodeCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "refactorCode",
  createCommandHandler = { languageServerWrapper, textEditorProvider ->
    RefactorCodeCommandHandler(TestScope(UnconfinedTestDispatcher()), languageServerWrapper, textEditorProvider)
  }
)
