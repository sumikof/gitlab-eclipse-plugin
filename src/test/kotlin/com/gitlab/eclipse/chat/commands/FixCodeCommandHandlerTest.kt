package com.gitlab.eclipse.chat.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class FixCodeCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "fixCode",
  createCommandHandler = { gitLabDuoChatWebViewClient, textEditorProvider ->
    FixCodeCommandHandler(TestScope(UnconfinedTestDispatcher()), gitLabDuoChatWebViewClient, textEditorProvider)
  }
)
