package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.listeners.ProjectOpenLanguageServerListener
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import org.koin.dsl.module

val languageServerModule = module {
  single<LanguageServerProxyManager> { LanguageServerProxyManager() }
  single<LanguageServerInstaller> { LanguageServerInstaller() }
  single<GitLabLanguageServerWrapper> { GitLabLanguageServerWrapper() }
  single<ProjectOpenLanguageServerListener>(createdAtStart = true) { ProjectOpenLanguageServerListener(get(), get()) }
  single<GitLabLanguageServerConfigurationService> { GitLabLanguageServerConfigurationService(get(), get(), get()) }
  single<GitLabLanguageServerProcessProvider> { GitLabLanguageServerProcessProvider(get(), get(), get(), get(), get()) }
  single<CodeSuggestionsApiStatusService> { CodeSuggestionsApiStatusService(get()) }
  single<LanguageServerWebviewService> { LanguageServerWebviewService(get(), get()) }
}
