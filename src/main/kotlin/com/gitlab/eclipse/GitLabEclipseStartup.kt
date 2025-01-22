package com.gitlab.eclipse

import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.utils.workspaceModule
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.startKoin
import org.osgi.framework.BundleContext

class GitLabEclipseStartup : AbstractUIPlugin() {
  override fun start(context: BundleContext) {
    startKoin {
      modules(workspaceModule(context), languageServerModule())
    }
  }
}
