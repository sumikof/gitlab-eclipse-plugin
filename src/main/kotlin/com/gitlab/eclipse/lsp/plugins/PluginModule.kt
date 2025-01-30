package com.gitlab.eclipse.lsp.plugins

import org.koin.dsl.module

val pluginModule = module {
  single<PluginRegistry> { PluginRegistry(getAll()) }
  single<PluginMessageService> { PluginMessageService(get()) }
}
