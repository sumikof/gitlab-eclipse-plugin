package com.gitlab.eclipse.lsp.plugins.messages

data class PluginMessage(
  val pluginId: String,
  val type: String,
  val payload: Any?
)
