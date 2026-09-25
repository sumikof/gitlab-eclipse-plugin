package com.gitlab.eclipse.knowledgegraph

/**
 * The graph's address out of an untyped `getUrl` response or `ready` payload, or `null` when there
 * is none (A20).
 *
 * Both carry `{ url }`, with `url` undefined until `gkg` has started. What arrives here is never a
 * DTO: the `ready` payload is deliberately taken as `Any?` (§15), and `$/gitlab/plugin/request` is
 * also the client-side receiver's name, so lsp4j parses the `getUrl` response with that receiver's
 * `Object` return type, i.e. into a Gson `LinkedTreeMap`. So only a [Map] whose `"url"` entry is a
 * non-blank [String] yields a value; anything else — null, a non-map, a missing, non-string or blank
 * `url` — yields `null`.
 *
 * Never throws. The entry is found by walking the keys rather than with `get`, because `get` on a
 * sorted map compares keys and so could throw for a map keyed by something other than strings.
 */
internal fun knowledgeGraphUrlOf(response: Any?): String? =
  (response as? Map<*, *>)?.entries
    ?.firstOrNull { it.key == "url" }
    ?.let { it.value as? String }
    ?.takeIf { it.isNotBlank() }
