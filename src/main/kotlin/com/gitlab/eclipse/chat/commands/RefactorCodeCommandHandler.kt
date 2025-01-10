package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class RefactorCodeCommandHandler(
  coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
  languageServerWrapper: GitLabLanguageServerWrapper = GitLabLanguageServerWrapper(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(
  command = "/refactor",
  coroutineScope = coroutineScope,
  languageServerWrapper = languageServerWrapper,
  textEditorProvider = textEditorProvider
)
