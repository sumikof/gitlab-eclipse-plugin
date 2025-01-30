package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope

class FixCodeCommandHandler(
  coroutineScope: CoroutineScope = service<CoroutineScope>(),
  gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient = service<GitLabDuoChatWebViewClient>(),
  textEditorProvider: TextEditorProvider = TextEditorProvider(),
) : ChatCommandHandler(
  promptType = "fixCode",
  coroutineScope = coroutineScope,
  gitLabDuoChatWebViewClient = gitLabDuoChatWebViewClient,
  textEditorProvider = textEditorProvider
)
