package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewController
import com.gitlab.eclipse.lsp.plugins.PluginController
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.services.ISourceProviderService
import org.koin.dsl.bind
import org.koin.dsl.module

val chatModule = module {
  single<DuoChatStateService> {
    PlatformUI
      .getWorkbench()
      .getService(ISourceProviderService::class.java)
      .getSourceProvider(DuoChatStateService.DUO_CHAT_ENABLED_KEY) as DuoChatStateService
  }

  single<CurrentFileContextProvider> { CurrentFileContextProvider() }
  single<EditorSelectionContextProvider> { EditorSelectionContextProvider() }
  single<InsertCodeSnippetService> { InsertCodeSnippetService(get(), get()) }

  single<GitLabDuoChatWebViewClient> { GitLabDuoChatWebViewClient(get()) }

  single<GitLabDuoChatWebViewController> {
    GitLabDuoChatWebViewController(get(), get(), get(), get())
  } bind PluginController::class
}
