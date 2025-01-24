package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import org.koin.dsl.module

fun languageServerModule() = module {
  single<GitLabLanguageServerWrapper> { GitLabLanguageServerWrapper() }
  single<GitLabLanguageServerConfigurationService> { GitLabLanguageServerConfigurationService(get(), get(), get()) }
  single<CodeSuggestionsApiStatusService> { CodeSuggestionsApiStatusService(get()) }
  single<LanguageServerWebviewService> { LanguageServerWebviewService(get(), get()) }
}
