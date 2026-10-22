package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.Resolved
import com.gitlab.eclipse.views.sidebar.SectionApply
import com.gitlab.eclipse.views.sidebar.SectionUpdate
import com.gitlab.eclipse.views.sidebar.autorefresh.CurrentBranchAutoRefresher
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.RefreshReason
import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import com.gitlab.eclipse.views.sidebar.autorefresh.SettleOutcome
import com.gitlab.eclipse.views.sidebar.autorefresh.SoftRefreshPort
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import com.gitlab.eclipse.views.sidebar.autorefresh.settleOutcomeOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** How a window entry fetches its section: [com.gitlab.eclipse.views.sidebar.CurrentBranchSectionFetcher.fetch]. */
fun interface CurrentBranchFetch {
  suspend fun fetch(
    editorFile: File?,
    previous: CurrentBranchSectionInput?,
    previousGitDir: String?,
    soft: Boolean,
    apply: SectionApply,
  ): TrackedHead?
}

/**
 * A subscriber of one window's state (status-bar design §4.1, delivery order R6). Every call is on the UI
 * thread: [onRefreshStarted] synchronously when a refresh launches; [onApplied] and then [onRefreshFinished]
 * through the same FIFO UI queue, so a refresh's finish always follows its applies. [onRefreshFinished]'s
 * outcome is the fetch's settle outcome, or null when it was cancelled or failed. A superseded refresh's
 * applies and finish are not delivered.
 */
interface CurrentBranchListener {
  fun onRefreshStarted(id: Long, soft: Boolean) = Unit

  fun onApplied(id: Long, snapshot: CurrentBranchSnapshot) = Unit

  fun onRefreshFinished(id: Long, outcome: SettleOutcome?) = Unit
}

/** The refresh in flight: a subscriber arriving now adopts [id] (R9). */
data class RunningRefresh(val id: Long, val soft: Boolean)

/**
 * One workbench window's current-branch state (status-bar design §4.1–§4.3, §5): the CI-04 refresher with this
 * window's activity, its own section fetch, the [snapshot] both the trim and the sidebar render, and its
 * subscribers.
 *
 * - [start] (once) starts the refresher and runs the first fetch (N1): `requestRefresh(INITIAL)`, which runs at
 *   once in an active window (an inactive one gets it from the FOCUS rule on activation); a refresher that does
 *   not start leaves one [refreshFull] and manual refreshes only.
 * - [refreshFull] is the manual Refresh: no previous value (placeholders), and it supersedes the running refresh
 *   with a manual ticket. [requestRefresh] goes through the refresher's coalescing and never supersedes (R5).
 * - Each fetch runs on [scope] (the shared IO scope, never cancelled as a whole); the editor file is read on the
 *   UI thread from THIS window ([editorFile], R16). Its applies and its end come back through [hop] (FIFO
 *   `asyncExec`), checked against the refresh id and [isDisposed]. The snapshot keeps exactly the instance the
 *   fetcher's update produced and hands it back as the next soft refresh's previous (R10).
 * - The settle records the work tree, canonicalised on the fetch's (IO) thread with [canonical], and the
 *   [HeadKind] (R4); an editor activated outside that work tree requests a BRANCH refresh, its file canonicalised
 *   the same way off the UI thread first (§4.3).
 * - [dispose] is idempotent: stops the refresher (its session guard makes late callbacks inert), removes the
 *   editor listener, cancels the fetch and drops the subscribers; every later hop sees [isDisposed] and does
 *   nothing.
 *
 * UI-thread confined, SWT-free (the SWT pieces are the injected ports). Logs carry exception class names only.
 */
@Suppress("TooManyFunctions")
class WindowCurrentBranchState(
  private val fetch: CurrentBranchFetch,
  private val editorFile: () -> File?,
  private val scope: CoroutineScope,
  private val hop: (() -> Unit) -> Unit,
  private val createRefresher: (SoftRefreshPort) -> CurrentBranchAutoRefresher,
  private val installEditorListener: ((File?) -> Unit) -> Registration = { Registration { } },
  private val canonical: (String) -> String = { File(it).canonicalPath },
  private val log: (String) -> Unit,
) {
  /** What one fetch learned once it returned: the head it looked at and the canonical work tree. */
  private class Finished(val head: TrackedHead?, val workTree: String?)

  /** The live state; changes with every start, apply and finish. */
  var snapshot = CurrentBranchSnapshot(version = 0, gitDir = null, workTree = null, input = null, refreshing = false)
    private set

  /**
   * The last snapshot whose input had settled — both sides of a [Resolved], or [NoRepository] — so a menu built
   * during a refresh (placeholders, half-settled) still sees the shown pipeline. Null until the first settle.
   */
  var settledSnapshot: CurrentBranchSnapshot? = null
    private set

  var runningRefresh: RunningRefresh? = null
    private set

  var isDisposed = false
    private set

  private var started = false
  private var refresher: CurrentBranchAutoRefresher? = null
  private var editorRegistration: Registration? = null
  private var job: Job? = null
  private var lastId = 0L

  /** No refresher: a request that arrived while a refresh ran, run once after it. */
  private var pendingWithoutRefresher = false
  private val listeners = mutableListOf<CurrentBranchListener>()

  fun start() {
    if (started || isDisposed) return
    started = true
    editorRegistration = contain("editor listener") { installEditorListener(::onEditorActivated) }
    val created = contain("auto refresh") {
      createRefresher { ticket -> launchRefresh(soft = true, ticket = ticket) }.also { it.start() }
    }
    refresher = created
    // A refresher that did not start (it logged why) answers false: fetch once by ourselves (N1).
    if (created == null || !created.requestRefresh(RefreshReason.INITIAL)) {
      refresher = null
      refreshFull()
    }
  }

  fun addListener(listener: CurrentBranchListener): Registration {
    if (isDisposed) return Registration { }
    listeners += listener
    return Registration { listeners.remove(listener) }
  }

  /** The manual Refresh: a full fetch that supersedes the running one (the refresher adopts its ticket). */
  fun refreshFull() {
    if (isDisposed) return
    val ticket = refresher?.let { r -> r.nextTicket().also { r.onManualRefreshStarted(it) } }
    launchRefresh(soft = false, ticket = ticket)
  }

  /** Any other refresh (editor switch, after a CI write): coalesced, never superseding (R5). */
  fun requestRefresh(reason: RefreshReason) {
    if (isDisposed) return
    val r = refresher
    when {
      r != null -> r.requestRefresh(reason)
      runningRefresh != null -> pendingWithoutRefresher = true
      else -> launchRefresh(soft = true, ticket = null)
    }
  }

  fun dispose() {
    if (isDisposed) return
    isDisposed = true
    contain("auto refresh stop") { refresher?.stop() }
    refresher = null
    contain("editor listener removal") { editorRegistration?.unregister() }
    editorRegistration = null
    job?.cancel()
    job = null
    runningRefresh = null
    listeners.clear()
  }

  /**
   * §4.3: an editor of this window was activated. Its file is canonicalised on [scope] (the same rule as the work
   * tree's, so a symlinked or differently cased path compares equal), then compared on the UI thread against the
   * work tree tracked at that moment.
   */
  private fun onEditorActivated(file: File?) {
    if (isDisposed || file == null) return
    val path = file.path
    contain("editor check") {
      scope.launch {
        val canonicalFile = canonicalOrSelf(path)
        hop {
          if (!isDisposed && RepositorySwitchRule.shouldRefresh(canonicalFile, snapshot.workTree)) {
            requestRefresh(RefreshReason.BRANCH)
          }
        }
      }
    }
  }

  private fun launchRefresh(soft: Boolean, ticket: Long?) {
    if (isDisposed) return
    job?.cancel()
    val id = ++lastId
    runningRefresh = RunningRefresh(id, soft)
    val file = contain("editor read") { editorFile() }
    val previous = if (soft) snapshot.input else null
    val previousGitDir = snapshot.gitDir
    update(snapshot.copy(refreshing = true))
    notifyListeners { it.onRefreshStarted(id, soft) }
    val recorded = AtomicReference<Finished?>()
    val launched = contain("refresh launch") {
      scope.launch {
        // Shared scope with a plain Job: nothing but cancellation may escape.
        try {
          val head = fetch.fetch(file, previous, previousGitDir, soft) { gitDir, change ->
            hop { onApply(id, gitDir, change) }
          }
          recorded.set(Finished(head, head?.workTree?.let(::canonicalOrSelf)))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          runCatching { log("Current-branch refresh failed: ${e.javaClass.name}") }
        }
      }
    }
    if (launched == null) {
      hop { onFinished(id, ticket, null) }
      return
    }
    job = launched
    // Fires however the job ends, even if it never ran (scope already cancelled): no ticket is ever lost.
    launched.invokeOnCompletion { cause ->
      hop { onFinished(id, ticket, if (cause == null) recorded.get() else null) }
    }
  }

  private fun onApply(id: Long, gitDir: String?, change: SectionUpdate) {
    if (isDisposed || runningRefresh?.id != id) return
    val input = change(snapshot.input)
    val sameRepository = gitDir == snapshot.gitDir
    update(
      snapshot.copy(
        gitDir = gitDir,
        input = input,
        workTree = if (sameRepository) snapshot.workTree else null,
        headKind = if (sameRepository) snapshot.headKind else HeadKind.UNKNOWN,
      ),
    )
    val applied = snapshot
    notifyListeners { it.onApplied(id, applied) }
  }

  private fun onFinished(id: Long, ticket: Long?, finished: Finished?) {
    if (isDisposed) return
    val outcome = finished?.let { settleOutcomeOf(it.head) }
    if (runningRefresh?.id == id) {
      runningRefresh = null
      job = null
      update(settled(snapshot.copy(refreshing = false), finished))
      notifyListeners { it.onRefreshFinished(id, outcome) }
    }
    // After the subscribers: a COALESCED refresh the refresher starts here must follow this finish.
    if (ticket != null) refresher?.onRefreshSettled(ticket, outcome ?: SettleOutcome.Unknown)
    if (pendingWithoutRefresher && runningRefresh == null && refresher == null) {
      pendingWithoutRefresher = false
      launchRefresh(soft = true, ticket = null)
    }
  }

  /** What the fetch's return adds to the applied state: work tree and head kind of the repository it looked at. */
  private fun settled(next: CurrentBranchSnapshot, finished: Finished?): CurrentBranchSnapshot {
    finished ?: return next
    val head = finished.head ?: return next.copy(workTree = null, headKind = HeadKind.UNKNOWN)
    if (head.gitDir != next.gitDir) return next
    return next.copy(workTree = finished.workTree, headKind = headKindOf(head))
  }

  private fun update(next: CurrentBranchSnapshot) {
    snapshot = next.copy(version = snapshot.version + 1)
    if (isSettled(snapshot.input)) settledSnapshot = snapshot
  }

  private fun notifyListeners(call: (CurrentBranchListener) -> Unit) {
    listeners.toList().forEach { listener ->
      try {
        call(listener)
      } catch (e: Exception) {
        runCatching { log("Current-branch listener failed: ${e.javaClass.name}") }
      }
    }
  }

  private fun canonicalOrSelf(path: String): String =
    try {
      canonical(path)
    } catch (@Suppress("SwallowedException") e: Exception) {
      path // compared as resolved: at worst one extra BRANCH refresh
    }

  private fun <T> contain(step: String, block: () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      runCatching { log("Current-branch $step failed: ${e.javaClass.name}") }
      null
    }
}

private fun isSettled(input: CurrentBranchSectionInput?): Boolean =
  input == NoRepository || (input is Resolved && input.mr != null && input.pipeline != null)

/** §4.5: a branch; a detached HEAD on exactly one tag (the tag pipeline); any other detached HEAD; unreadable. */
internal fun headKindOf(head: TrackedHead): HeadKind =
  when (head.head) {
    is HeadKey.Branch -> HeadKind.BRANCH
    is HeadKey.Detached -> if (head.onSingleTag) HeadKind.TAG else HeadKind.DETACHED
    HeadKey.Unavailable -> HeadKind.UNKNOWN
  }
