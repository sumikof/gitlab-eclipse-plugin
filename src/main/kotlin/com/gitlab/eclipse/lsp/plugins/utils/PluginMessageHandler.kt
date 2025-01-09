package com.gitlab.eclipse.lsp.plugins.utils

import java.util.function.Function

data class PluginMessageHandler(
  val type: Class<*>?,
  val handle: Function<Any?, Any?>
)
