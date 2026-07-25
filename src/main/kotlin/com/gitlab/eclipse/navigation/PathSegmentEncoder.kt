package com.gitlab.eclipse.navigation

/** RFC 3986 percent-encoding for URL path segments. Preserves `/` in encodePath; encodes everything non-unreserved. */
object PathSegmentEncoder {
  private const val BYTE_MASK = 0xFF
  private const val HEX_FORMAT = "%02X"

  private fun isUnreserved(c: Int): Boolean =
    c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
      c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code

  fun encodeSegment(s: String): String = buildString {
    for (b in s.toByteArray(Charsets.UTF_8)) {
      val c = b.toInt() and BYTE_MASK
      if (isUnreserved(c)) append(c.toChar()) else append('%').append(HEX_FORMAT.format(c))
    }
  }

  fun encodePath(path: String): String =
    path.split('/').joinToString("/") { encodeSegment(it) }
}
