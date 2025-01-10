package com.gitlab.eclipse.lsp.plugins.utils

data class PluginMessageRoute(val pluginId: String, val type: PluginMessageType, val method: String) {
  override fun toString(): String {
    return "$type: $pluginId/$method"
  }
}
