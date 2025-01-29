package com.gitlab.eclipse

import com.gitlab.eclipse.chat.chatModule
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.utils.workspaceModule
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.startKoin
import org.osgi.framework.BundleContext

class GitLabEclipseStartup : AbstractUIPlugin() {
  override fun start(context: BundleContext) {
    startKoin {
      modules(
        workspaceModule(context),
        chatModule,
        languageServerModule
      )
    }

    service<GitLabLanguageServerProcessProvider>().start()
  }

  override fun stop(context: BundleContext) {
    service<GitLabLanguageServerProcessProvider>().stop()
  }
}
