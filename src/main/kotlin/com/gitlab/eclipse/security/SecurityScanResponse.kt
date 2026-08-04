package com.gitlab.eclipse.security

/**
 * Body of the language server's remote security scan response.
 *
 * Every field is optional with a `null` default on purpose: this is parsed from a foreign process,
 * so a payload that omits a field, or a whole payload that never arrives, has to be representable
 * without throwing. Consumers decide what a missing field means rather than relying on a default
 * that would silently look like a real value.
 *
 * [results] is intentionally untyped. The finding shape is the language server's, and pinning it
 * down here would make an unrecognised finding fail to parse instead of simply being ignored.
 *
 * [error] carries the server's own message. It is shown to nobody and logged nowhere: it can quote
 * the scanned file, its path or the response body.
 */
data class SecurityScanResponse(
  val filePath: String? = null,
  val status: Int? = null,
  val results: List<Any?>? = null,
  val error: String? = null,
  val timestamp: Long? = null,
)
