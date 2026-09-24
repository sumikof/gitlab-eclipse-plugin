package com.gitlab.eclipse.lsp.plugins.messages

/**
 * The params of an extension → plugin `$/gitlab/plugin/request`: the server's
 * `handleRequestMessage` takes exactly `{ pluginId, type, payload }` and routes on the first two.
 *
 * Same shape as [ExtensionToPluginNotification], kept apart so a request can never be sent through
 * the notification's type (or the reverse) by accident, and distinct from [PluginMessage], which is
 * what the *server* sends the other way.
 */
data class ExtensionToPluginRequest(
  val pluginId: String,
  val type: String,
  val payload: Any? = null
)
