package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import java.util.Collections
import java.util.IdentityHashMap

// CI-06 §4.6 (R1-1 / R2-1 / R2-2 / R2-3): carrying the lazy-load state of downstream pipeline nodes
// across a rebuild. Pure and SWT-free; it mutates nodes, so — like every write to a node's load
// state — it runs on the SWT UI thread only.

/**
 * Moves the lazy-load state of the [DownstreamPipelineNode]s of [oldRoots] onto the freshly built ones
 * of [newRoots], and reports the old in-flight loads the rebuild abandoned.
 *
 * Called where a rebuild swaps one tree for another: in `applyCompose` after the coordinator composed
 * the new input and before it becomes `viewer.input`, and by the refresh sweep before it re-renders a
 * downstream node whose children it rebuilt (so its grandchildren keep their state).
 *
 * A fresh (never-loaded) new node takes over an old node's state — `loadState`, `loadedChildren`,
 * `shownPayload` and `loadGeneration` — when the old one has the same downstream pipeline id, is
 * LOADED, and was fetched over the same connection: same instance URL (normalized) AND same auth
 * fingerprint. A different account or instance transfers nothing, so the new node re-fetches over
 * the current connection instead of keeping the old account's data on screen. The children move as
 * the same instances, which is what lets the viewer keep their expansion and lets nested downstream
 * nodes come along with their own state. The old node's generation is bumped so a sweep result still
 * in flight for it is dropped.
 *
 * Old nodes are found at any depth, through the children of LOADED downstream nodes, so a nested node
 * rebuilt by the sweep finds its predecessor too. A new node that IS an old instance (the coordinator
 * kept the subtree) is left alone. An old node that was LOADING is never transferred: its generation is
 * bumped (its result will be dropped) and it is returned, unless it is still part of the new tree, so
 * the caller cancels its job; its fresh counterpart stays NOT_LOADED and is loaded by the caller when
 * it is expanded.
 */
internal fun transferDownstreamState(
  oldRoots: List<SidebarNode>,
  newRoots: List<SidebarNode>,
): List<DownstreamPipelineNode> {
  val oldNodes = downstreamNodesIn(oldRoots)
  if (oldNodes.isEmpty()) return emptyList()
  val oldInstances = identitySetOf(oldNodes)
  // First one wins, and each old node is handed out once: two new nodes must never share children.
  val donors = LinkedHashMap<Long, DownstreamPipelineNode>()
  oldNodes.filter { it.loadState == DownstreamLoadState.LOADED }.forEach { donors.putIfAbsent(it.pipelineId, it) }
  downstreamNodesIn(newRoots)
    .filter { it !in oldInstances && it.isFresh() }
    .forEach { fresh ->
      val donor = donors[fresh.pipelineId]
      if (donor != null && sameSource(donor, fresh)) {
        donors.remove(fresh.pipelineId)
        moveLoadState(donor, fresh)
      }
    }
  val kept = identitySetOf(downstreamNodesIn(newRoots))
  return oldNodes
    .filter { it.loadState == DownstreamLoadState.LOADING && it !in kept }
    .onEach { it.loadGeneration++ }
}

/**
 * Every [DownstreamPipelineNode] at or under [roots], in tree order, descending into a downstream
 * node's children only when it is LOADED (anything else holds the "Loading…" placeholder or a message).
 */
internal fun downstreamNodesIn(roots: List<SidebarNode>): List<DownstreamPipelineNode> {
  val found = mutableListOf<DownstreamPipelineNode>()
  fun visit(node: SidebarNode) {
    if (node is DownstreamPipelineNode) {
      found += node
      if (node.loadState != DownstreamLoadState.LOADED) return
    }
    node.children.forEach(::visit)
  }
  roots.forEach(::visit)
  return found
}

/** Never loaded nor loading: what a node built by a rebuild looks like. */
private fun DownstreamPipelineNode.isFresh(): Boolean =
  expandable && loadState == DownstreamLoadState.NOT_LOADED && loadedChildren == null

/** Same instance (normalized URL) and same account (fingerprint) — the write gate's comparison. */
private fun sameSource(old: DownstreamPipelineNode, new: DownstreamPipelineNode): Boolean =
  normalizeInstanceUrl(old.sourceInstanceUrl) == normalizeInstanceUrl(new.sourceInstanceUrl) &&
    old.sourceAuthFingerprint == new.sourceAuthFingerprint

private fun moveLoadState(from: DownstreamPipelineNode, to: DownstreamPipelineNode) {
  to.loadState = from.loadState
  to.loadedChildren = from.loadedChildren
  to.shownPayload = from.shownPayload
  to.loadGeneration = from.loadGeneration
  from.loadGeneration++
}

private fun identitySetOf(nodes: List<DownstreamPipelineNode>): Set<DownstreamPipelineNode> =
  Collections.newSetFromMap(IdentityHashMap<DownstreamPipelineNode, Boolean>()).apply { addAll(nodes) }
