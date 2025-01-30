package com.gitlab.eclipse.chat.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class GenerateTestsCommandHandlerTest : ChatCommandHandlerTest(
  promptTypeUnderTest = "generateTests",
  createCommandHandler = { gitLabDuoChatWebViewClient, textEditorProvider ->
    GenerateTestsCommandHandler(TestScope(UnconfinedTestDispatcher()), gitLabDuoChatWebViewClient, textEditorProvider)
  }
)
