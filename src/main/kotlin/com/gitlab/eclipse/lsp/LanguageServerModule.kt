package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.authentication.GitLabOAuthService
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.authentication.PatTokenProvider
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.lsp.edit.WorkspaceEditApplier
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.listeners.ProjectOpenLanguageServerListener
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import com.gitlab.eclipse.security.SecurityScanLauncher
import com.gitlab.eclipse.security.SecurityScanSaveListener
import com.gitlab.eclipse.security.SecurityScanSettings
import com.gitlab.eclipse.utils.LANGUAGE_SERVER_OUTBOUND
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

  single<ProjectOpenLanguageServerListener>(createdAtStart = true) {
    ProjectOpenLanguageServerListener(get(), get(), get(named(LANGUAGE_SERVER_OUTBOUND)))
  }
  single<GitLabLanguageServerConfigurationService> {
    GitLabLanguageServerConfigurationService(get(), get(), get(), get(named(LANGUAGE_SERVER_OUTBOUND)))
  }
  single<DidChangeWatchedFileCapability>(createdAtStart = true) { DidChangeWatchedFileCapability(get(), get()) }

  single<GitLabLanguageServerProcessProvider> {
    GitLabLanguageServerProcessProvider(get(), get(), get(), get(), get(), get())
  }

  single<LanguageServerWebviewService> { LanguageServerWebviewService(get(), get()) }

  single<DiagnosticMarkerService> { DiagnosticMarkerService() }

  // The four server -> client handlers of GitLabLanguageServerClient. Every constructor argument of
  // each one is a lambda with a production default, so building them touches neither SWT nor the
  // workbench; Koin builds them on first use, which is the first message of that kind to arrive.
  single<WorkspaceEditApplier> { WorkspaceEditApplier() }
  single<WorkspaceFileOpener> { WorkspaceFileOpener() }
  single<CopyTextHandler> { CopyTextHandler() }
  single<ShowDocumentLauncher> { ShowDocumentLauncher() }

  single<SecurityScanLauncher> {
    SecurityScanLauncher(get(), get(), get(), get(), get(), get(named(LANGUAGE_SERVER_OUTBOUND)))
  }

  // The save trigger is a singleton because it is stateful in a way that has to be paired: it holds
  // every document provider and page it attached to, and GitLabEclipseStartup.stop() detaches from
  // exactly those. A second instance would leak the first one's registrations.
  single<SecurityScanSaveListener> { SecurityScanSaveListener() }

  single<SecurityScanSettings> {
    SecurityScanSettings(get(), get(named(LANGUAGE_SERVER_OUTBOUND)), get())
  }
}
