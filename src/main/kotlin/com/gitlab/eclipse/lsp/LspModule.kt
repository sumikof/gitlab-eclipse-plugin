package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import org.koin.dsl.module

const val PROPERTY_LANGUAGE_SERVER = "language-server"

val lspModule = module {
  single { GitLabLanguageServerWrapper() }

  single { getProperty(PROPERTY_LANGUAGE_SERVER) as GitLabLanguageServer }

  single { GitLabLanguageServerConfigurationService(get()) }

  single { LanguageServerProxyManager() }

  single { LanguageServerInstaller() }
}