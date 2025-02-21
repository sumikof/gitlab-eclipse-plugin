package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope

class RefactorCodeCommandHandler(
  coroutineScope: CoroutineScope = service<CoroutineScope>(),
  gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient = service<GitLabDuoChatWebViewClient>(),
  platformUtils: PlatformUtils = service<PlatformUtils>(),
) : ChatCommandHandler(
  promptType = "refactorCode",
  coroutineScope = coroutineScope,
  gitLabDuoChatWebViewClient = gitLabDuoChatWebViewClient,
  platformUtils = platformUtils
)
