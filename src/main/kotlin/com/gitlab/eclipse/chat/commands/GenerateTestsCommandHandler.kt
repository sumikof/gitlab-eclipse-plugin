package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope

class GenerateTestsCommandHandler(
  coroutineScope: CoroutineScope = service<CoroutineScope>(),
  gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient = service<GitLabDuoChatWebViewClient>(),
  platformUtils: PlatformUtils = PlatformUtils(),
) : ChatCommandHandler(
  promptType = "generateTests",
  coroutineScope = coroutineScope,
  gitLabDuoChatWebViewClient = gitLabDuoChatWebViewClient,
  platformUtils = platformUtils
)
