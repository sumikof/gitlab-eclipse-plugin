package com.gitlab.eclipse.utils

private const val BYTE_MASK = 0xFF
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 2

private fun isUnreservedByte(byte: Int): Boolean =
  byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code || byte in '0'.code..'9'.code ||
    byte == '-'.code || byte == '_'.code || byte == '.'.code || byte == '~'.code

/**
 * RFC 3986 `unreserved`-set percent-encoding of [value]'s UTF-8 bytes. Every byte outside
 * `ALPHA / DIGIT / - . _ ~` (including `/`) is emitted as `%XX`. Shared by callers that each
 * decide how to compose the result differently (e.g. a path encoder keeps `/` as a literal
 * separator outside this function by splitting on it first; a query-component encoder passes
 * every byte through, `/` included).
 */
internal fun percentEncodeUnreserved(value: String): String = buildString {
  for (raw in value.toByteArray(Charsets.UTF_8)) {
    val byte = raw.toInt() and BYTE_MASK
    if (isUnreservedByte(byte)) {
      append(byte.toChar())
    } else {
      append('%')
      append(byte.toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0'))
    }
  }
}
