package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class GenerateTestsCommandHandler(
  coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(
  promptType = "generateTests",
  coroutineScope = coroutineScope,
  textEditorProvider = textEditorProvider
)
