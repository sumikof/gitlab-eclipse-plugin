package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewController
import com.gitlab.eclipse.lsp.plugins.PluginController
import org.koin.dsl.bind
import org.koin.dsl.module

val chatModule = module {
  single<GitLabDuoChatWebViewController> { GitLabDuoChatWebViewController() } bind PluginController::class
}
