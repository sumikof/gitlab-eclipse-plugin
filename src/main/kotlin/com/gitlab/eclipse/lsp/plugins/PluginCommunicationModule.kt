package com.gitlab.eclipse.lsp.plugins

import org.koin.dsl.module

val pluginCommunicationModule = module {
  single { PluginMessageService(getAll()) }
}
