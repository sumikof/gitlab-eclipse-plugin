package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewClient
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewController
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewController
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.utils.NotificationUtils
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

  // P1-K: resolve the workbench-created source provider instance (declared in plugin.xml) so the
  // Koin singleton IS the provider that fires `duo_chat_available` source changes.
  single<ChatAvailabilityService> {
    PlatformUI
      .getWorkbench()
      .getService(ISourceProviderService::class.java)
      .getSourceProvider(ChatAvailabilityService.DUO_CHAT_AVAILABLE_KEY) as ChatAvailabilityService
  }

  single<CurrentFileContextProvider> { CurrentFileContextProvider() }
  single<EditorSelectionContextProvider> { EditorSelectionContextProvider() }
  single<InsertCodeSnippetService> { InsertCodeSnippetService(get(), get()) }

  single<GitLabDuoChatWebViewClient> { GitLabDuoChatWebViewClient(get()) }

  single<AgenticChatWebViewClient> { AgenticChatWebViewClient(get(), NotificationUtils::show) }

  single<GitLabDuoChatWebViewController> {
    GitLabDuoChatWebViewController(get(), get(), get(), get())
  } bind PluginController::class

  single<AgenticChatWebViewController> {
    AgenticChatWebViewController(get(), get(), get(), get())
  } bind PluginController::class
}
