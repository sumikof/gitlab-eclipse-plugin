package com.gitlab.eclipse.chat.quickchat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val HEX_RADIX = 16
private const val ESCAPE_LENGTH = 3

/**
 * Turns a `namespaceWithPath` taken from a remote URL into the `fullPath` the project query expects
 * (design §9.2.2, A25): an HTTP remote keeps percent-escapes, e.g. a non-ASCII group name.
 *
 * Each `/`-separated segment is percent-decoded exactly once as UTF-8. `+` stays a `+` (this is a
 * path, not a query string). Returns null — so preflight fails closed rather than asking about a
 * different project — on a malformed escape, on bytes that are not valid UTF-8, or when a segment
 * decodes to a `/` (which would change the path's structure).
 */
fun decodeFullPath(namespaceWithPath: String): String? {
  val segments = namespaceWithPath.split('/').map { decodeSegment(it) ?: return null }
  return segments.joinToString("/")
}

private fun decodeSegment(segment: String): String? {
  if ('%' !in segment) return segment
  val bytes = ByteArrayOutputStream()
  var i = 0
  while (i < segment.length) {
    val c = segment[i]
    if (c == '%') {
      if (i + ESCAPE_LENGTH > segment.length) return null
      val high = Character.digit(segment[i + 1], HEX_RADIX)
      val low = Character.digit(segment[i + 2], HEX_RADIX)
      if (high < 0 || low < 0) return null
      bytes.write(high * HEX_RADIX + low)
      i += ESCAPE_LENGTH
    } else {
      // Encode the whole literal run at once so a surrogate pair is never split into two halves.
      val end = segment.indexOf('%', i).takeIf { it >= 0 } ?: segment.length
      val literal = segment.substring(i, end).toByteArray(StandardCharsets.UTF_8)
      bytes.write(literal, 0, literal.size)
      i = end
    }
  }
  val decoded = strictUtf8(bytes.toByteArray()) ?: return null
  return decoded.takeUnless { '/' in it }
}

private fun strictUtf8(bytes: ByteArray): String? = try {
  StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()
} catch (_: CharacterCodingException) {
  null
}
