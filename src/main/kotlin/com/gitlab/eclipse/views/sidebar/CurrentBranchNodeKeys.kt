package com.gitlab.eclipse.views.sidebar

// CI-04: stable keys for the current-branch section's nodes, which a soft refresh may rebuild —
// used to carry expansion across the rebuild ([remapExpandedElements]) and to find what the
// rebuild made stale ([staleMrKeys]). Pure and SWT-free.

/**
 * The node of [newSection] with the stable key path of [element], an old node found (by identity) in
 * [oldSection]. Null when [element] was not in [oldSection] or has no counterpart. See [remapInChildren].
 */
internal fun remapInSection(element: Any, oldSection: SidebarNode, newSection: SidebarNode): SidebarNode? =
  remapInChildren(element, oldSection.children, newSection.children)

/**
 * The node of [newChildren]'s subtree that has the same stable key path as [element], an old node
 * found by identity in [oldChildren]'s subtree; null when [element] is not there or the path has no
 * counterpart. The path is followed key by key from the top, so every key is scoped to its parent:
 * an MR by (project id, iid), a pipeline by id, a stage by [StageNode.name] — never by label, which
 * carries the status (CI-05 §4.4) and would collapse the stage on every status change — the
 * "Downstream pipelines" group by its parent pipeline's id, and a downstream pipeline by its own id
 * (CI-06 §4.6). A node on the path without a key (a message, an MR's files and discussions) ends the
 * walk with null, so such elements keep passing through unchanged as before.
 *
 * Used for the current-branch section's soft rebuild ([remapInSection]) and by the downstream sweep
 * when it rebuilds one downstream node's children. Inside a downstream node whose state was carried
 * over ([transferDownstreamState]) the children are the same instances, so the walk lands on them.
 * Pure and SWT-free; UI thread only like its callers (it reads lazily loaded children).
 */
internal fun remapInChildren(
  element: Any,
  oldChildren: List<SidebarNode>,
  newChildren: List<SidebarNode>,
): SidebarNode? {
  val path = pathTo(element, oldChildren) ?: return null
  var candidates = newChildren
  var match: SidebarNode? = null
  for (step in path) {
    match = candidates.firstOrNull { sameStableKey(step, it) } ?: return null
    candidates = match.children
  }
  return match
}

/** The nodes from a member of [nodes] down to [element] (inclusive), found by identity; null when absent. */
private fun pathTo(element: Any, nodes: List<SidebarNode>): List<SidebarNode>? {
  for (node in nodes) {
    if (node === element) return listOf(node)
    val below = pathTo(element, node.children)
    if (below != null) return listOf(node) + below
  }
  return null
}

private fun sameStableKey(old: SidebarNode, new: SidebarNode): Boolean =
  when (old) {
    is MergeRequestNode -> new is MergeRequestNode && new.mr.projectId == old.mr.projectId && new.mr.iid == old.mr.iid
    is PipelineNode -> new is PipelineNode && new.pipelineId == old.pipelineId
    is StageNode -> new is StageNode && new.name == old.name
    is DownstreamPipelinesNode -> new is DownstreamPipelinesNode && new.parentPipelineId == old.parentPipelineId
    is DownstreamPipelineNode -> new is DownstreamPipelineNode && new.pipelineId == old.pipelineId
    else -> false
  }

/** What to invalidate when a soft refresh replaces current-branch MR nodes ([staleMrKeys]). */
internal data class StaleMrKeys(val discussionNodeIds: Set<Long>, val mrCacheEntries: Set<String>)

/**
 * CI-04 (design §2.4): the [MergeRequestNode]s of [oldChildren] that [newChildren] no longer
 * holds (by identity) were rebuilt by a soft refresh, so their diff-version cache entries
 * ([mrCacheKey]) and the registry keys of their discussion sections must be dropped — the
 * latter so an in-flight discussion load for the replaced subtree reports Superseded. A reused
 * MR side keeps the same instances and yields nothing. UI thread only, like its callers.
 */
internal fun staleMrKeys(oldChildren: List<SidebarNode>, newChildren: List<SidebarNode>): StaleMrKeys {
  val replaced = oldChildren.filterIsInstance<MergeRequestNode>().filter { old -> newChildren.none { it === old } }
  return StaleMrKeys(
    discussionNodeIds = collectDiscussionSectionNodeIds(replaced),
    mrCacheEntries = replaced.mapTo(mutableSetOf()) { mrCacheKey(it.mr.webUrl, it.mr.projectId, it.mr.iid) },
  )
}
