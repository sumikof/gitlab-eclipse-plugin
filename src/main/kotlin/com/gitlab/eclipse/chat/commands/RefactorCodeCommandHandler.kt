package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope

class RefactorCodeCommandHandler(
  coroutineScope: CoroutineScope = service<CoroutineScope>(),
  gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient = service<GitLabDuoChatWebViewClient>(),
  textEditorProvider: TextEditorProvider = service<TextEditorProvider>(),
) : ChatCommandHandler(
  promptType = "refactorCode",
  coroutineScope = coroutineScope,
  gitLabDuoChatWebViewClient = gitLabDuoChatWebViewClient,
  textEditorProvider = textEditorProvider
)
