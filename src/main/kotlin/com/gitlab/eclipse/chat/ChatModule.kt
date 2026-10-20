package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.chat.quickchat.AiCompletionStreamOpener
import com.gitlab.eclipse.chat.quickchat.ApiClientQuickChatConnections
import com.gitlab.eclipse.chat.quickchat.GraphQlQuickChatApi
import com.gitlab.eclipse.chat.quickchat.QuickChatApi
import com.gitlab.eclipse.chat.quickchat.QuickChatConnections
import com.gitlab.eclipse.chat.quickchat.QuickChatPoller
import com.gitlab.eclipse.chat.quickchat.QuickChatPreflight
import com.gitlab.eclipse.chat.quickchat.QuickChatRuntime
import com.gitlab.eclipse.chat.quickchat.QuickChatRuntimeLifecycle
import com.gitlab.eclipse.chat.quickchat.QuickChatService
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.chat.terminal.TerminalContextSourceProvider
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewClient
import com.gitlab.eclipse.chat.webview.AgenticChatWebViewController
import com.gitlab.eclipse.chat.webview.DuoAgentPlatformWebViewController
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewController
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.ShowDocumentLauncher
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.views.webview.actions.McpDashboardOpener
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayoutSourceProvider
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformReadiness
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

  // Same as above: the Koin singleton must be the workbench-created provider, or its source
  // changes would reach no one.
  single<TerminalContextSourceProvider> {
    PlatformUI
      .getWorkbench()
      .getService(ISourceProviderService::class.java)
      .getSourceProvider(TerminalContextSourceProvider.ENABLED_KEY) as TerminalContextSourceProvider
  }

  // Same again (design AGENT-02 §6): a second instance would never be registered with the workbench,
  // so whoever resolves the layout provider must get the one the workbench created and reads.
  single<AgentPlatformLayoutSourceProvider> {
    PlatformUI
      .getWorkbench()
      .getService(ISourceProviderService::class.java)
      .getSourceProvider(AgentPlatformLayoutSourceProvider.LAYOUT_KEY) as AgentPlatformLayoutSourceProvider
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

  // AGENT-02 design §5.3 / §5.4. Readiness is UI-confined: timerExec runs its check on the UI thread.
  single<AgentPlatformReadiness> {
    val wrapper = get<GitLabLanguageServerWrapper>()
    AgentPlatformReadiness(
      schedule = { ms, task -> currentDisplay.timerExec(ms.toInt(), task) },
      currentSession = { wrapper.currentSnapshot?.session },
    )
  }

  single<DuoAgentPlatformWebViewController> {
    val wrapper = get<GitLabLanguageServerWrapper>()
    DuoAgentPlatformWebViewController(
      get(),
      get(),
      get(),
      wrapper,
      get(),
      get(),
      openMcpDashboard = { McpDashboardOpener(wrapper).open() },
      showDocument = { uri -> ShowDocumentLauncher().show(uri) },
      onUiThread = { task -> currentDisplay.asyncExec(task) },
    )
  } bind PluginController::class

  // Quick Chat (design §8.1, §17): one runtime per activation, shared by every window's popup.
  // Its abandoned-job count is deliberately not here but in the QuickChatDetachedJobs object.
  // Created through the lifecycle holder so the bundle stop can close it (GitLabEclipseStartup.stop).
  single<QuickChatRuntime> { QuickChatRuntimeLifecycle.create() }
  single<QuickChatApi> { GraphQlQuickChatApi(get()) }
  single<QuickChatConnections> { ApiClientQuickChatConnections(get()) }
  single<QuickChatPreflight> {
    val resolver = GitLabProjectUrlResolver()
    QuickChatPreflight(get(), resolver::resolveProjectForFile)
  }
  single<QuickChatPoller> { QuickChatPoller(get(), get()) }
  single<QuickChatService> { QuickChatService(get(), get(), get(), get(), streams = AiCompletionStreamOpener(get())) }
}
