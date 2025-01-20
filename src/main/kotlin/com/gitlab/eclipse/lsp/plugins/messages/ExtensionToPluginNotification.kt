package com.gitlab.eclipse.lsp.plugins.messages

data class ExtensionToPluginNotification(
  val pluginId: String,
  val type: String,
  val payload: Any?
)
