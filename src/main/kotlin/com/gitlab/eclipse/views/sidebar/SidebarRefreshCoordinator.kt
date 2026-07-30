package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo

// Same glyph (U+2026) as SidebarViewModel's private LOADING_MESSAGE — Task 9 may consolidate.
private const val LOADING_MESSAGE = "Loading…"
private const val ISSUES_ROOT_TITLE = "Issues assigned to me"
private const val MRS_ROOT_TITLE = "Merge requests assigned to me"

/** Memo-key sentinel meaning "nothing composed yet" (distinct from a legitimate `null` key). */
private object None

/** Memo-key for a settled `Result.success(null)` (distinct from `null` = fetch still pending). */
private object NullSuccess

/**
 * Composes the sidebar's full `viewer.input` list — the two assigned query roots plus the
 * "For current branch" section — from one refresh generation's [RefreshSlots] (design doc
 * §6.6/§9/§10, R7-R9). Pure and SWT-free: no widgets, no threads, no generation/dispose
 * checks (Task 9's view owns those and calls [compose] on the UI thread only).
 *
 * The coordinator memoizes what it built so a re-compose after one slot settles does not
 * rebuild — and thereby replace — the nodes of the slots that didn't change. JFace tracks
 * tree elements by identity, so returning the *same* node instances is what preserves a
 * subtree's expansion state and an expanded [MergeRequestNode]'s in-flight lazy-children
 * fetch. Three memo layers:
 *
 * - assigned: the two query roots are reused while the `assigned` slot value and mode are
 *   unchanged (including the Pending placeholder roots — [Slot.Pending] is a singleton);
 * - current-branch section: the whole section node is reused while the `currentBranch` slot
 *   value is unchanged;
 * - within the section: the MR-side and pipeline-side child lists are memoized separately,
 *   keyed by identity of [Resolved.mr] / [Resolved.pipeline], so a pipeline update rebuilds
 *   the section node but splices the *same* [MergeRequestNode] (and closing-issue nodes)
 *   back in — and vice versa.
 *
 * All memo state is dropped when [RefreshSlots.generation] differs from the last composed
 * generation: a new refresh must never reuse (or leak) a prior generation's nodes.
 */
class SidebarRefreshCoordinator(private val viewModel: SidebarViewModel) {

  private var lastGeneration: Long? = null

  private var assignedKey: Any? = None
  private var assignedMode: SidebarViewMode? = null
  private var assignedNodes: List<SidebarNode> = emptyList()

  private var sectionKey: Any? = None
  private var sectionNode: SidebarNode = CurrentBranchSectionNode(emptyList())

  private var mrSideKey: Any? = None
  private var mrSideNodes: List<SidebarNode> = emptyList()

  private var pipelineSideKey: Any? = None
  private var pipelineSideNodes: List<SidebarNode> = emptyList()

  fun compose(slots: RefreshSlots, mode: SidebarViewMode): List<SidebarNode> {
    if (slots.generation != lastGeneration) {
      resetMemo()
      lastGeneration = slots.generation
    }
    return composeAssigned(slots.assigned, mode) + composeCurrentBranch(slots.currentBranch)
  }

  private fun composeAssigned(
    slot: Slot<Pair<Result<List<GitLabIssue>>, Result<List<GitLabMergeRequest>>>>,
    mode: SidebarViewMode,
  ): List<SidebarNode> {
    val key = keyOf(slot)
    if (assignedKey === key && assignedMode == mode) return assignedNodes
    val nodes =
      when (slot) {
        is Slot.Pending -> loadingRoots()
        is Slot.Settled -> viewModel.buildRoots(slot.value.first, slot.value.second, mode)
      }
    assignedKey = key
    assignedMode = mode
    assignedNodes = nodes
    return nodes
  }

  private fun composeCurrentBranch(slot: Slot<CurrentBranchSectionInput>): SidebarNode {
    val key = keyOf(slot)
    if (sectionKey === key) return sectionNode
    val node =
      when (slot) {
        is Slot.Pending -> CurrentBranchSectionNode(listOf(MessageNode(LOADING_MESSAGE)))
        is Slot.Settled -> buildSection(slot.value)
      }
    sectionKey = key
    sectionNode = node
    return node
  }

  private fun buildSection(input: CurrentBranchSectionInput): SidebarNode =
    when (input) {
      is NoRepository -> viewModel.buildCurrentBranchSection(input)
      is Resolved -> CurrentBranchSectionNode(pipelineSide(input.pipeline) + mrSide(input.mr))
    }

  /**
   * MR-side children (MergeRequestNode + closing issues, or the Loading/failure message),
   * memoized by [mr]'s identity. Built through the Task-7 overload with a settled-empty
   * pipeline — `Result.success(null)` contributes zero pipeline children by contract (see
   * [SidebarViewModel.buildCurrentBranchSection]'s KDoc: "no row at all"), so the built
   * section's children ARE exactly the MR side. Keeps the rendering byte-identical to the
   * view-model without duplicating its private message constants here.
   */
  private fun mrSide(mr: Result<CurrentBranchInfo>?): List<SidebarNode> {
    val key = resultKey(mr)
    if (mrSideKey === key) return mrSideNodes
    val nodes = viewModel.buildCurrentBranchSection(Resolved(mr = mr, pipeline = Result.success(null))).children
    mrSideKey = key
    mrSideNodes = nodes
    return nodes
  }

  /**
   * Pipeline-side children (PipelineNode subtree, Loading, empty, or failure message),
   * memoized by [pipeline]'s identity. Built through the Task-7 overload with a pending MR —
   * `mr = null` contributes exactly one trailing "Loading…" node by contract (see
   * [SidebarViewModel.buildCurrentBranchSection]'s KDoc), so dropping the last child leaves
   * exactly the pipeline side.
   */
  private fun pipelineSide(pipeline: Result<PipelineSnapshot?>?): List<SidebarNode> {
    val key = resultKey(pipeline)
    if (pipelineSideKey === key) return pipelineSideNodes
    val nodes = viewModel.buildCurrentBranchSection(Resolved(mr = null, pipeline = pipeline)).children.dropLast(1)
    pipelineSideKey = key
    pipelineSideNodes = nodes
    return nodes
  }

  private fun loadingRoots(): List<SidebarNode> =
    listOf(
      QueryRootNode(ISSUES_ROOT_TITLE, listOf(MessageNode(LOADING_MESSAGE))),
      QueryRootNode(MRS_ROOT_TITLE, listOf(MessageNode(LOADING_MESSAGE))),
    )

  private fun resetMemo() {
    assignedKey = None
    assignedMode = null
    assignedNodes = emptyList()
    sectionKey = None
    sectionNode = CurrentBranchSectionNode(emptyList())
    mrSideKey = None
    mrSideNodes = emptyList()
    pipelineSideKey = None
    pipelineSideNodes = emptyList()
  }
}

/**
 * Identity key for a slot: [Slot.Pending] is a singleton so it keys itself; a settled slot
 * keys by its payload's identity (the view may wrap the same payload in a fresh [Slot.Settled],
 * which must still count as "unchanged").
 */
private fun keyOf(slot: Slot<Any?>): Any? =
  when (slot) {
    is Slot.Pending -> slot
    is Slot.Settled -> slot.value
  }

/**
 * Identity key for a [Resolved] side's `Result`. `Result` is a value class, so reference
 * identity on the `Result` itself is unstable (each pass into a nullable field re-boxes it,
 * and the compiler prohibits `===` on it outright); the payload — the success value or the
 * failure [Throwable] — is a regular object whose reference IS stable, and "same payload
 * reference" is exactly what "this side's fetch result hasn't changed" means here. `null`
 * (fetch still pending) keys as `null`; a settled `Result.success(null)` (e.g. "no current
 * pipeline") keys as [NullSuccess] so it can never collide with pending.
 */
private fun resultKey(result: Result<*>?): Any? =
  result?.fold(
    onSuccess = { it ?: NullSuccess },
    onFailure = { it },
  )
