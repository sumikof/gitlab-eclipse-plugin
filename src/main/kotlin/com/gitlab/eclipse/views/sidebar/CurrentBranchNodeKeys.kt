package com.gitlab.eclipse.views.sidebar

// CI-04: stable keys for the current-branch section's nodes, which a soft refresh may rebuild —
// used to carry expansion across the rebuild ([remapExpandedElements]) and to find what the
// rebuild made stale ([staleMrKeys]). Pure and SWT-free.

/**
 * The node of [newSection] with the stable key of [element], an old node found (by identity) in
 * [oldSection]: an MR by (project id, iid), a pipeline by id, a stage by its pipeline's id and its
 * label. Null when [element] was not in [oldSection] or has no counterpart.
 */
internal fun remapInSection(element: Any, oldSection: SidebarNode, newSection: SidebarNode): SidebarNode? {
  val oldChildren = oldSection.children
  return when {
    oldChildren.none { it === element } -> remapStage(element, oldChildren, newSection.children)
    element is MergeRequestNode -> newSection.children.firstOrNull {
      it is MergeRequestNode && it.mr.projectId == element.mr.projectId && it.mr.iid == element.mr.iid
    }
    element is PipelineNode -> newSection.children.pipelineWithId(element.pipelineId)
    else -> null
  }
}

/** A stage of an old section pipeline, remapped within the new pipeline of the same id by label. */
private fun remapStage(element: Any, oldChildren: List<SidebarNode>, newChildren: List<SidebarNode>): SidebarNode? {
  if (element !is StageNode) return null
  val oldPipeline = oldChildren.filterIsInstance<PipelineNode>().firstOrNull { pipeline ->
    pipeline.children.any { it === element }
  } ?: return null
  val newPipeline = newChildren.pipelineWithId(oldPipeline.pipelineId) ?: return null
  return newPipeline.children.firstOrNull { it is StageNode && it.label == element.label }
}

private fun List<SidebarNode>.pipelineWithId(id: Long): PipelineNode? =
  filterIsInstance<PipelineNode>().firstOrNull { it.pipelineId == id }

/** What to invalidate when a soft refresh replaces current-branch MR nodes ([staleMrKeys]). */
internal data class StaleMrKeys(val discussionNodeIds: Set<Long>, val mrCacheKeys: Set<String>)

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
    mrCacheKeys = replaced.mapTo(mutableSetOf()) { mrCacheKey(it.mr.webUrl, it.mr.projectId, it.mr.iid) },
  )
}
