package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.currentbranch.CurrentBranchListener
import com.gitlab.eclipse.currentbranch.CurrentBranchSnapshot
import com.gitlab.eclipse.currentbranch.RunningRefresh
import com.gitlab.eclipse.currentbranch.WindowCurrentBranchState
import com.gitlab.eclipse.views.sidebar.autorefresh.SettleOutcome

/**
 * The sidebar's "For current branch" section as a subscriber of its window's shared state (status-bar design §4.4):
 * the view no longer fetches the section itself. SWT-free; the view supplies the ports.
 *
 * - Each refresh of the state is bound, when it starts, to the view generation's [RefreshSlots] shown then and a
 *   new current-branch epoch (R9: `id → (slots, epoch)`); [cancelSweep] first, as the soft refresh did (CI-06
 *   §4.6 R2-4). Its applies set the slot to the state's snapshot — the exact instance the fetch produced (R10) —
 *   at once, in the callback (UI thread, FIFO with the state's other callbacks), and only the re-compose is
 *   deferred through [apply] (the view's applyCompose: a second `asyncExec`, gated on the generation). Setting the
 *   slot inside that deferred step instead would lose it whenever the refresh's finish starts a coalesced refresh
 *   (new epoch) before the compose runs — and that refresh, seeing unchanged data, applies nothing. Its finish
 *   requests the downstream sweep only for a normal finish (non-null outcome), gated on its epoch. A refresh superseded by a newer one never
 *   finishes: starting a refresh forgets every older binding, so nothing waits for it.
 * - [initial] (createPartControl, or a mode change before anything was fetched): a window that already has a
 *   value is seeded from it and fetches nothing (R5: the window's refresher owns staleness); a refresh in flight is
 *   adopted; a window with nothing settled and nothing running gets one full fetch (N1).
 * - [manual] (the Refresh button): a full fetch of the window state, which supersedes its running refresh.
 * - Without a window state (the service could not provide one) the section settles as [NoRepository] rather than
 *   staying "Loading…" forever.
 *
 * UI thread only (the listener callbacks and both entry points run there).
 */
class CurrentBranchSidebarBinding(
  private val currentSlots: () -> RefreshSlots?,
  private val cancelSweep: () -> Unit,
  private val apply: (RefreshSlots, ApplyScope, Boolean, () -> Unit) -> Unit,
  private val requestSweep: (RefreshSlots, ApplyScope.CurrentBranch) -> Unit,
) : CurrentBranchListener {
  private class Binding(val slots: RefreshSlots, val scope: ApplyScope.CurrentBranch, val soft: Boolean)

  private val bindings = mutableMapOf<Long, Binding>()

  /** Refreshes bound and not yet finished: at most one (tests check that superseded ones are forgotten). */
  val boundRefreshes: Int
    get() = bindings.size

  fun initial(state: WindowCurrentBranchState?, slots: RefreshSlots) {
    if (state == null) {
      settleWithoutState(slots)
      return
    }
    val snapshot = state.snapshot
    val running = state.runningRefresh
    when {
      snapshot.input != null -> seed(slots, snapshot, running)
      running != null -> bind(running.id, slots, running.soft)
      else -> state.refreshFull() // binds through onRefreshStarted to the slots the view just installed
    }
  }

  fun manual(state: WindowCurrentBranchState?, slots: RefreshSlots) {
    if (state == null) settleWithoutState(slots) else state.refreshFull()
  }

  override fun onRefreshStarted(id: Long, soft: Boolean) {
    val slots = currentSlots() ?: return
    bind(id, slots, soft)
  }

  override fun onApplied(id: Long, snapshot: CurrentBranchSnapshot) {
    val binding = bindings[id] ?: return
    val input = snapshot.input ?: return
    binding.slots.currentBranch = Slot.Settled(input)
    binding.slots.currentBranchGitDir = snapshot.gitDir
    apply(binding.slots, ApplyScope.Generation, binding.soft) {}
  }

  override fun onRefreshFinished(id: Long, outcome: SettleOutcome?) {
    val binding = bindings.remove(id) ?: return
    if (outcome != null) requestSweep(binding.slots, binding.scope)
  }

  /** The window's current value into fresh slots, adopting the refresh that may still be changing it. */
  private fun seed(slots: RefreshSlots, snapshot: CurrentBranchSnapshot, running: RunningRefresh?) {
    if (running != null) bind(running.id, slots, running.soft)
    slots.currentBranch = Slot.Settled(checkNotNull(snapshot.input))
    slots.currentBranchGitDir = snapshot.gitDir
    apply(slots, ApplyScope.Generation, false) {}
  }

  /** A new current-branch epoch of [slots]: the sweep of anything bound earlier to them is dropped by its gate. */
  private fun bind(id: Long, slots: RefreshSlots, soft: Boolean) {
    cancelSweep()
    bindings.clear() // a newer refresh started: the older ones are superseded and never finish
    slots.currentBranchEpoch++
    bindings[id] = Binding(slots, ApplyScope.CurrentBranch(slots.currentBranchEpoch), soft)
  }

  private fun settleWithoutState(slots: RefreshSlots) {
    slots.currentBranch = Slot.Settled(NoRepository)
    slots.currentBranchGitDir = null
    apply(slots, ApplyScope.Generation, false) {}
  }
}
