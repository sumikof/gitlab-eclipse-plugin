package com.gitlab.eclipse

import com.gitlab.eclipse.lsp.lspModule
import com.gitlab.eclipse.telemetry.telemetryModule
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.GlobalContext.startKoin
import org.osgi.framework.BundleContext

class GitLabEclipseStartup : AbstractUIPlugin() {
  override fun start(context: BundleContext?) {
    super.start(context)

    startKoin {
      modules(
        lspModule,
        telemetryModule
      )
    }
  }
}
