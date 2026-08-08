package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.utils.percentEncodeUnreserved

/** RFC 3986 percent-encoding for URL path segments. Preserves `/` in encodePath; encodes everything non-unreserved. */
object PathSegmentEncoder {
  fun encodeSegment(s: String): String = percentEncodeUnreserved(s)

  fun encodePath(path: String): String =
    path.split('/').joinToString("/") { encodeSegment(it) }
}
