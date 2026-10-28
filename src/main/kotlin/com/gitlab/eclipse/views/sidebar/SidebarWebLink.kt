package com.gitlab.eclipse.views.sidebar

import java.net.URI

/** Name of the `hasWebUrl` property served by [SidebarLinkPropertyTester]. */
const val HAS_WEB_URL_PROPERTY = "hasWebUrl"

private val OPENABLE_SCHEMES = setOf("http", "https")

/**
 * The trimmed [raw] when it is an http(s) URL with a host, else null (null, blank, unparsable, other scheme,
 * no host). The scheme is lower-cased only to decide; the returned string is the input as given.
 * A display / UX decision only: the security decision before a link leaves for the browser is `BrowserLauncher` (D8a).
 */
fun openableWebUrl(raw: String?): String? {
  val trimmed = raw?.trim().orEmpty()
  if (trimmed.isEmpty()) return null
  val uri = try {
    URI(trimmed)
  } catch (_: java.net.URISyntaxException) {
    return null
  }
  return if (uri.scheme?.lowercase() in OPENABLE_SCHEMES && uri.host != null) trimmed else null
}

/**
 * The web URL "Open in GitLab" would open for [node], or null when it has none. A [MergeRequestNode] is not
 * activatable (its [SidebarNode.activationUrl] is null) but still has a [MergeRequestNode.url].
 * An allow-list, failing closed: a new node type with an [SidebarNode.activationUrl] is not openable until added here.
 */
fun sidebarWebUrl(node: SidebarNode): String? =
  when (node) {
    is MergeRequestNode -> openableWebUrl(node.url)
    is IssueNode, is OverviewNode, is PipelineNode, is JobNode, is BridgeNode, is DownstreamPipelineNode ->
      openableWebUrl(node.activationUrl)
    else -> null
  }
