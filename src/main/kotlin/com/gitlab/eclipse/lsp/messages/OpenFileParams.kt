package com.gitlab.eclipse.lsp.messages

/**
 * Parameters of the language server's `$/gitlab/openFile` notification.
 *
 * [filePath] is nullable on purpose: lsp4j deserialises this with Gson, which constructs the
 * object without running the Kotlin constructor, so a missing or explicit-null JSON field
 * yields `null` here regardless of the declared nullability. Declaring it nullable makes that
 * null visible to the compiler instead of surfacing as an NPE at an unrelated call site.
 */
data class OpenFileParams(val filePath: String?)

/**
 * Returns [value] when it carries a usable payload, or null when the server sent nothing usable.
 * Blank is treated as absent: a path or a clipboard payload of spaces is not actionable.
 */
fun usablePayload(value: String?): String? {
  if (value.isNullOrBlank()) return null
  return value
}
