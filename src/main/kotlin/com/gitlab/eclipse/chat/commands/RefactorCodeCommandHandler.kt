package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope

class RefactorCodeCommandHandler(
  coroutineScope: CoroutineScope = service<CoroutineScope>(),
  languageServerWrapper: GitLabLanguageServerWrapper = service<GitLabLanguageServerWrapper>(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(
  promptType = "refactorCode",
  coroutineScope = coroutineScope,
  languageServerWrapper = languageServerWrapper,
  textEditorProvider = textEditorProvider
)
