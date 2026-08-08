package com.gitlab.eclipse.lsp.plugins.utils

import com.gitlab.eclipse.lsp.LanguageServerSession
import java.util.function.BiFunction

/**
 * @param type the type the JSON payload is parsed into, or null when the handler takes no payload.
 *   A handler whose only parameter is a [LanguageServerSession] takes no payload, so its [type] is
 *   null: this field says what the message must carry, never what the handler was given.
 * @param handle invoked with the parsed payload and the connection the message was sent from, the
 *   latter being null when the caller could not name one.
 */
data class PluginMessageHandler(
  val type: Class<*>?,
  val handle: BiFunction<Any?, LanguageServerSession?, Any?>
)
