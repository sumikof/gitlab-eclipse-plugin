package com.gitlab.eclipse

import com.gitlab.eclipse.chat.chatModule
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.lsp.plugins.pluginCommunicationModule
import com.gitlab.eclipse.utils.workspaceModule
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.startKoin
import org.osgi.framework.BundleContext

class GitLabEclipseStartup : AbstractUIPlugin() {
  override fun start(context: BundleContext) {
    val koinApplication = startKoin {
      modules(workspaceModule(context), chatModule(), languageServerModule())
    }

    // For discoverability reasons, this module must be instantiated after the application modules have been registered.
    koinApplication.modules(pluginCommunicationModule())

    service<GitLabLanguageServerProcessProvider>().start()
  }

  override fun stop(context: BundleContext) {
    service<GitLabLanguageServerProcessProvider>().stop()
  }
}
