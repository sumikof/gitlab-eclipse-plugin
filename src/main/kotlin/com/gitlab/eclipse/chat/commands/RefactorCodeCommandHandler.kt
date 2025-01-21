package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.di.service
import com.gitlab.eclipse.di.Workspace
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class RefactorCodeCommandHandler(
  coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
  languageServerWrapper: GitLabLanguageServerWrapper = Workspace.service(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(
  promptType = "refactorCode",
  coroutineScope = coroutineScope,
  languageServerWrapper = languageServerWrapper,
  textEditorProvider = textEditorProvider
)
