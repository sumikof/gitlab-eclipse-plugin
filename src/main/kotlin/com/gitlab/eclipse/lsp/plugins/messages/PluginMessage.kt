package com.gitlab.eclipse.lsp.plugins.messages

import com.google.gson.JsonElement

data class PluginMessage(
  val pluginId: String,
  val type: String,
  val payload: JsonElement?
)
