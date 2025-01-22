package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope

class ExplainCodeCommandHandler(
  coroutineScope: CoroutineScope = service<CoroutineScope>(),
  languageServerWrapper: GitLabLanguageServerWrapper = service<GitLabLanguageServerWrapper>(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(
  promptType = "explainCode",
  coroutineScope = coroutineScope,
  languageServerWrapper = languageServerWrapper,
  textEditorProvider = textEditorProvider
)
