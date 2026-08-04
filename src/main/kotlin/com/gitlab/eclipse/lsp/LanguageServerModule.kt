package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.authentication.GitLabOAuthService
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.authentication.PatTokenProvider
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.listeners.ProjectOpenLanguageServerListener
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import org.koin.core.qualifier.named
import org.koin.dsl.module

val languageServerModule = module {
  single<PatTokenProvider> { PatTokenProvider() }
  single<OAuthTokenProvider> { OAuthTokenProvider() }
  single<GitLabOAuthService> { GitLabOAuthService() }
  single<GitLabTokenProviderManager> { GitLabTokenProviderManager() }

  single<LanguageServerProxyManager> { LanguageServerProxyManager() }
  single<LanguageServerInstaller> { LanguageServerInstaller() }
  single<GitLabLanguageServerWrapper> { GitLabLanguageServerWrapper() }
  single<GitDiffService> { GitDiffService() }

  single<GitLabLanguageServerOpenFilesService>(createdAtStart = true) {
    GitLabLanguageServerOpenFilesService(get(), get(), get())
  }

  single<ProjectOpenLanguageServerListener>(createdAtStart = true) { ProjectOpenLanguageServerListener(get(), get()) }
  single<GitLabLanguageServerConfigurationService> {
    GitLabLanguageServerConfigurationService(get(), get(), get(), get(named("languageServerOutbound")))
  }
  single<DidChangeWatchedFileCapability>(createdAtStart = true) { DidChangeWatchedFileCapability(get(), get()) }

  single<GitLabLanguageServerProcessProvider> {
    GitLabLanguageServerProcessProvider(get(), get(), get(), get(), get(), get())
  }

  single<LanguageServerWebviewService> { LanguageServerWebviewService(get(), get()) }

  single<DiagnosticMarkerService> { DiagnosticMarkerService() }
}
