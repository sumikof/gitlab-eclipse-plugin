package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.Resolved
import com.gitlab.eclipse.views.sidebar.SectionApply
import com.gitlab.eclipse.views.sidebar.autorefresh.CurrentBranchAutoRefresher
import com.gitlab.eclipse.views.sidebar.autorefresh.FakeActivity
import com.gitlab.eclipse.views.sidebar.autorefresh.FakeProbe
import com.gitlab.eclipse.views.sidebar.autorefresh.FakeRefs
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.RefreshReason
import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import com.gitlab.eclipse.views.sidebar.autorefresh.SettleOutcome
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import com.gitlab.eclipse.views.sidebar.autorefresh.VirtualTime
import com.gitlab.eclipse.views.sidebar.autorefresh.immediateScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.io.File

private const val PERIOD = 30_000L
private const val GIT_DIR = "/home/alice/secret-repo/.git"
private const val WORK_TREE = "/link/secret-repo"
private const val CANONICAL_WORK_TREE = "/home/alice/secret-repo"
private val EDITOR_FILE = File("/home/alice/secret-repo/src/Main.kt")
internal val BRANCH_HEAD = TrackedHead(GIT_DIR, HeadKey.Branch("feature/secret"), workTree = WORK_TREE)

/** A fetch the test drives by hand: each call waits for [Call.finish]; its applies go out through [Call.apply]. */
internal class FakeFetch : CurrentBranchFetch {
  class Call(
    val editorFile: File?,
    val previous: CurrentBranchSectionInput?,
    val previousGitDir: String?,
    val soft: Boolean,
    val onHead: (TrackedHead) -> Unit,
    val apply: SectionApply,
  ) {
    val result = CompletableDeferred<TrackedHead?>()
    var cancelled = false

    fun finish(head: TrackedHead?) {
      result.complete(head)
    }

    fun fail(e: Exception) {
      result.completeExceptionally(e)
    }
  }

  val calls = mutableListOf<Call>()

  override suspend fun fetch(
    editorFile: File?,
    previous: CurrentBranchSectionInput?,
    previousGitDir: String?,
    soft: Boolean,
    onHead: (TrackedHead) -> Unit,
    apply: SectionApply,
  ): TrackedHead? {
    val call = Call(editorFile, previous, previousGitDir, soft, onHead, apply)
    calls += call
    try {
      return call.result.await()
    } catch (e: CancellationException) {
      call.cancelled = true
      throw e
    }
  }
}

/** The UI thread's `asyncExec` queue: FIFO, runs nothing until [drain]. */
internal class UiQueue {
  private val queue = ArrayDeque<() -> Unit>()
  val hop: (() -> Unit) -> Unit = { queue += it }

  fun drain() {
    while (queue.isNotEmpty()) queue.removeFirst()()
  }
}

/** Records every listener callback as one line. */
internal class StateRecorder : CurrentBranchListener {
  val events = mutableListOf<String>()
  val snapshots = mutableListOf<CurrentBranchSnapshot>()

  override fun onRefreshStarted(id: Long, soft: Boolean) {
    events += "started $id soft=$soft"
  }

  override fun onApplied(id: Long, snapshot: CurrentBranchSnapshot) {
    events += "applied $id"
    snapshots += snapshot
  }

  override fun onRefreshFinished(id: Long, outcome: SettleOutcome?) {
    events += "finished $id ${outcome?.javaClass?.simpleName}"
  }
}

internal class StateRig(active: Boolean = true, refresherFails: Boolean = false, editor: File? = EDITOR_FILE) {
  val time = VirtualTime()
  val activity = FakeActivity(active)
  val refs = FakeRefs().apply { if (refresherFails) failRegister = IllegalStateException(GIT_DIR) }
  val probe = FakeProbe()
  val ui = UiQueue()
  val fetch = FakeFetch()
  val logs = mutableListOf<String>()
  var editorFile: File? = editor
  val editorListeners = mutableListOf<(File?) -> Unit>()
  var editorUnregistered = 0
  val parentJob = Job()
  var refresher: CurrentBranchAutoRefresher? = null

  val state = WindowCurrentBranchState(
    fetch = fetch,
    editorFile = { editorFile },
    scope = immediateScope(parentJob),
    hop = ui.hop,
    createRefresher = { port ->
      CurrentBranchAutoRefresher(time, time, activity, refs, probe, port, log = { logs += it }).also { refresher = it }
    },
    installEditorListener = { listener ->
      editorListeners += listener
      Registration { editorUnregistered++ }
    },
    canonical = { it.replace("/link/", "/home/alice/") },
    log = { logs += it },
  )
  val recorder = StateRecorder()

  fun started(): StateRig = apply {
    state.addListener(recorder)
    state.start()
  }

  /** The latest fetch call applies [input] for [gitDir] (an update that ignores the current value). */
  fun applyLast(input: CurrentBranchSectionInput, gitDir: String? = GIT_DIR) {
    fetch.calls.last().apply(gitDir) { input }
  }

  /** The latest fetch paints both sides of a branch and finishes on [BRANCH_HEAD]; the UI queue is drained. */
  fun settleBranch(input: Resolved = resolvedBranch()): Resolved {
    applyLast(input)
    fetch.calls.last().finish(BRANCH_HEAD)
    ui.drain()
    return input
  }
}

private val NO_MR = CurrentBranchInfo(null, emptyList())

internal fun resolvedBranch(): Resolved = Resolved(Result.success(NO_MR), Result.success(NoPipeline))

class WindowCurrentBranchStateTest : StringSpec({
  "1a. N1: an entry started in an active window fetches exactly once at once (soft, INITIAL), with this window's editor" {
    val rig = StateRig().started()

    rig.fetch.calls shouldHaveSize 1
    rig.fetch.calls.single().soft shouldBe true
    rig.fetch.calls.single().editorFile shouldBe EDITOR_FILE
    rig.fetch.calls.single().previous.shouldBeNull()
    rig.state.snapshot.refreshing shouldBe true
    rig.state.runningRefresh.shouldNotBeNull().soft shouldBe true
  }

  "1b. N1: an entry started in an inactive window fetches nothing until activated, then once (FOCUS)" {
    val rig = StateRig(active = false).started()
    rig.fetch.calls.shouldBeEmpty()
    rig.state.snapshot.input.shouldBeNull()
    rig.state.snapshot.refreshing shouldBe false

    rig.activity.set(true)

    rig.fetch.calls shouldHaveSize 1
  }

  "1c. N1: a refresher that fails to start leaves one full fetch, and later requests still fetch (manual only)" {
    val rig = StateRig(refresherFails = true).started()

    rig.fetch.calls shouldHaveSize 1
    rig.fetch.calls.single().soft shouldBe false
    rig.settleBranch()
    rig.state.snapshot.input.shouldNotBeNull()

    rig.state.requestRefresh(RefreshReason.MANUAL)
    rig.fetch.calls shouldHaveSize 2
    rig.fetch.calls.last().soft shouldBe true
    rig.logs.forEach { it shouldNotContain "secret" }
  }

  "1d. without a refresher a request during a running refresh is kept and runs once after it" {
    val rig = StateRig(refresherFails = true).started()

    rig.state.requestRefresh(RefreshReason.BRANCH)
    rig.state.requestRefresh(RefreshReason.BRANCH)
    rig.fetch.calls shouldHaveSize 1

    rig.settleBranch()
    rig.fetch.calls shouldHaveSize 2
  }

  "1e. start() twice starts one refresher and one fetch" {
    val rig = StateRig().started()
    rig.state.start()

    rig.activity.registered shouldBe 1
    rig.fetch.calls shouldHaveSize 1
    rig.editorListeners shouldHaveSize 1
  }

  "2a. applies are delivered in order, carry the exact fetched instance (R10) and bump the version" {
    val rig = StateRig().started()
    val placeholder = Resolved(null, null)
    val settled = resolvedBranch()

    rig.applyLast(placeholder)
    rig.applyLast(settled)
    rig.recorder.events shouldContainExactly listOf("started 1 soft=true")
    rig.ui.drain()

    rig.recorder.events shouldContainExactly listOf("started 1 soft=true", "applied 1", "applied 1")
    rig.recorder.snapshots[0].input shouldBeSameInstanceAs placeholder
    rig.recorder.snapshots[1].input shouldBeSameInstanceAs settled
    rig.state.snapshot.input shouldBeSameInstanceAs settled
    rig.state.snapshot.gitDir shouldBe GIT_DIR
    (rig.recorder.snapshots[1].version > rig.recorder.snapshots[0].version) shouldBe true
  }

  "2b. R6: onRefreshFinished comes through the same FIFO queue, after that refresh's applies, with the settle outcome" {
    val rig = StateRig().started()
    rig.applyLast(resolvedBranch())
    rig.fetch.calls.last().finish(BRANCH_HEAD)
    rig.recorder.events shouldContainExactly listOf("started 1 soft=true")

    rig.ui.drain()

    rig.recorder.events shouldContainExactly listOf("started 1 soft=true", "applied 1", "finished 1 Looked")
    rig.state.snapshot.refreshing shouldBe false
    rig.state.runningRefresh.shouldBeNull()
  }

  "2c. R10: the soft refresh after a settle hands the fetcher the stored instance and gitDir as previous" {
    val rig = StateRig().started()
    val first = rig.settleBranch()

    rig.time.advanceBy(PERIOD)

    rig.fetch.calls shouldHaveSize 2
    rig.fetch.calls.last().previous shouldBeSameInstanceAs first
    rig.fetch.calls.last().previousGitDir shouldBe GIT_DIR
  }

  "2d. the settle records the canonical work tree (on the fetch's thread) and the head kind" {
    val rig = StateRig().started()
    rig.settleBranch()

    rig.state.snapshot.workTree shouldBe CANONICAL_WORK_TREE
    rig.state.snapshot.headKind shouldBe HeadKind.BRANCH
  }

  "2e. head kinds: detached on a single tag is TAG, other detached is DETACHED, unreadable is UNKNOWN" {
    listOf(
      TrackedHead(GIT_DIR, HeadKey.Detached("abc"), WORK_TREE, onSingleTag = true) to HeadKind.TAG,
      TrackedHead(GIT_DIR, HeadKey.Detached("abc"), WORK_TREE, onSingleTag = false) to HeadKind.DETACHED,
      TrackedHead(GIT_DIR, HeadKey.Unavailable, WORK_TREE) to HeadKind.UNKNOWN,
    ).forEach { (head, kind) ->
      val rig = StateRig().started()
      rig.applyLast(resolvedBranch())
      rig.fetch.calls.last().finish(head)
      rig.ui.drain()
      rig.state.snapshot.headKind shouldBe kind
    }
  }

  "2f. a settle without a repository clears gitDir, work tree and head kind" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)

    rig.applyLast(NoRepository, gitDir = null)
    rig.fetch.calls.last().finish(null)
    rig.ui.drain()

    rig.state.snapshot.input shouldBe NoRepository
    rig.state.snapshot.gitDir.shouldBeNull()
    rig.state.snapshot.workTree.shouldBeNull()
    rig.state.snapshot.headKind shouldBe HeadKind.UNKNOWN
    rig.recorder.events.last() shouldBe "finished 2 NoRepository"
  }

  "2g. an apply for another repository resets the work tree and head kind until that refresh settles" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)

    rig.applyLast(Resolved(null, null), gitDir = "/other/.git")
    rig.ui.drain()

    rig.state.snapshot.gitDir shouldBe "/other/.git"
    rig.state.snapshot.workTree.shouldBeNull()
    rig.state.snapshot.headKind shouldBe HeadKind.UNKNOWN
  }

  "2h. a work tree that cannot be canonicalised is kept as resolved" {
    val rig = StateRig()
    val state = WindowCurrentBranchState(
      fetch = rig.fetch,
      editorFile = { null },
      scope = immediateScope(),
      hop = rig.ui.hop,
      createRefresher = { port ->
        CurrentBranchAutoRefresher(rig.time, rig.time, rig.activity, rig.refs, rig.probe, port, log = {})
      },
      canonical = { throw java.io.IOException(it) },
      log = { rig.logs += it },
    )
    state.start()
    rig.applyLast(resolvedBranch())
    rig.fetch.calls.last().finish(BRANCH_HEAD)
    rig.ui.drain()

    state.snapshot.workTree shouldBe WORK_TREE
    rig.logs.forEach { it shouldNotContain "secret" }
  }

  "2i. T3b: the head kind reported before the sides holds from the first side's apply: a tag never shows an MR item" {
    val rig = StateRig().started()
    val tag = TrackedHead(GIT_DIR, HeadKey.Detached("abc"), WORK_TREE, onSingleTag = true)
    val call = rig.fetch.calls.single()

    rig.applyLast(Resolved(null, null))
    call.onHead(tag)
    rig.applyLast(Resolved(Result.success(NO_MR), null)) // the MR side settles first
    rig.ui.drain()

    // The placeholder painted before the report shows the loading items (design §4.5 row 1), never "Create MR".
    StatusTrimPresenter.present(rig.recorder.snapshots.first(), null).mr?.text shouldBe "Finding MR…"
    rig.recorder.snapshots.drop(1).forEach { StatusTrimPresenter.present(it, null).mr.shouldBeNull() }
    rig.recorder.snapshots.last().headKind shouldBe HeadKind.TAG
    rig.recorder.snapshots.last().workTree shouldBe CANONICAL_WORK_TREE
  }

  "2j. T3b: a detached HEAD without a single tag hides everything from the first side's apply on" {
    val rig = StateRig().started()
    val detached = TrackedHead(GIT_DIR, HeadKey.Detached("abc"), WORK_TREE, onSingleTag = false)
    val call = rig.fetch.calls.single()

    rig.applyLast(Resolved(null, null))
    call.onHead(detached)
    rig.applyLast(Resolved(Result.success(NO_MR), null))
    rig.applyLast(Resolved(Result.success(NO_MR), Result.success(NoPipeline)))
    rig.ui.drain()

    rig.recorder.snapshots.drop(1).forEach { StatusTrimPresenter.present(it, null) shouldBe TrimModel.HIDDEN }
  }

  "2k. T3b: the head kind stays for the whole refresh, also when the repository changes with the placeholder" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)
    val call = rig.fetch.calls.last()
    val other = TrackedHead("/other/.git", HeadKey.Detached("abc"), "/other", onSingleTag = true)

    call.onHead(other) // reported before the placeholder for the new repository reaches the UI thread
    rig.applyLast(Resolved(null, null), gitDir = "/other/.git")
    rig.applyLast(Resolved(Result.success(NO_MR), null), gitDir = "/other/.git")
    rig.ui.drain()

    rig.recorder.snapshots.takeLast(2).forEach { it.headKind shouldBe HeadKind.TAG }
    rig.state.snapshot.workTree shouldBe "/other"
  }

  "2l. T3b: a superseded refresh's head report is ignored" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)
    val stale = rig.fetch.calls.last()
    rig.state.refreshFull()

    stale.onHead(TrackedHead(GIT_DIR, HeadKey.Detached("abc"), WORK_TREE))
    rig.ui.drain()

    rig.state.snapshot.headKind shouldBe HeadKind.BRANCH
  }

  "3a. refreshFull supersedes the running soft refresh: full fetch, old applies and finish dropped, manual ticket settles" {
    val rig = StateRig().started()
    val soft = rig.fetch.calls.single()

    rig.state.refreshFull()

    rig.fetch.calls shouldHaveSize 2
    val full = rig.fetch.calls.last()
    full.soft shouldBe false
    full.previous.shouldBeNull()
    soft.cancelled shouldBe true
    soft.apply(GIT_DIR) { resolvedBranch() } // late apply of the superseded refresh
    rig.ui.drain()
    rig.recorder.events shouldContainExactly listOf("started 1 soft=true", "started 2 soft=false")
    rig.state.snapshot.input.shouldBeNull()

    rig.settleBranch()
    rig.recorder.events shouldContainExactly listOf(
      "started 1 soft=true",
      "started 2 soft=false",
      "applied 2",
      "finished 2 Looked",
    )
    // The manual settle re-armed the refresher: the next timer refresh comes one period later.
    rig.time.advanceBy(PERIOD)
    rig.fetch.calls shouldHaveSize 3
  }

  "3b. R5: requestRefresh never supersedes: it coalesces into one refresh after the running one settles" {
    val rig = StateRig().started()

    rig.state.requestRefresh(RefreshReason.BRANCH)
    rig.state.requestRefresh(RefreshReason.MANUAL)
    rig.fetch.calls shouldHaveSize 1

    rig.settleBranch()
    rig.fetch.calls shouldHaveSize 2
    rig.fetch.calls.last().soft shouldBe true
  }

  "3c. a fetch that fails unexpectedly finishes with a null outcome, logs the class only, and the refresher retries next period" {
    val rig = StateRig().started()

    rig.fetch.calls.last().fail(IllegalStateException(GIT_DIR))
    rig.ui.drain()

    rig.recorder.events.last() shouldBe "finished 1 null"
    rig.logs.single() shouldBe "Current-branch refresh failed: java.lang.IllegalStateException"
    rig.time.advanceBy(PERIOD)
    rig.fetch.calls shouldHaveSize 2
  }

  "3d. a refresh cancelled with the shared scope finishes with a null outcome" {
    val rig = StateRig().started()

    rig.parentJob.cancel()
    rig.ui.drain()

    rig.recorder.events.last() shouldBe "finished 1 null"
    rig.state.snapshot.refreshing shouldBe false
  }

  "3e. a launch on an already-cancelled scope still finishes the refresh (never a stuck ticket)" {
    val rig = StateRig()
    rig.parentJob.cancel()
    rig.started()
    rig.ui.drain()

    rig.recorder.events shouldContainExactly listOf("started 1 soft=true", "finished 1 null")
    rig.time.advanceBy(PERIOD)
    rig.ui.drain()
    rig.recorder.events.last() shouldBe "finished 2 null"
  }

  "4a. settledSnapshot: the last snapshot with both sides settled (or no repository), kept through a full refresh" {
    val rig = StateRig().started()
    rig.state.settledSnapshot.shouldBeNull()
    val first = rig.settleBranch()
    rig.state.settledSnapshot.shouldNotBeNull().input shouldBeSameInstanceAs first
    rig.state.settledSnapshot.shouldNotBeNull().headKind shouldBe HeadKind.BRANCH

    rig.state.refreshFull()
    rig.applyLast(Resolved(null, null))
    rig.applyLast(Resolved(Result.success(NO_MR), null))
    rig.ui.drain()

    rig.state.snapshot.input shouldBe Resolved(Result.success(NO_MR), null)
    rig.state.settledSnapshot.shouldNotBeNull().input shouldBeSameInstanceAs first

    rig.applyLast(NoRepository, gitDir = null)
    rig.ui.drain()
    rig.state.settledSnapshot.shouldNotBeNull().input shouldBe NoRepository
  }

  "5a. a listener added during a running refresh receives that refresh's applies and finish" {
    val rig = StateRig()
    rig.state.start()
    val id = rig.state.runningRefresh.shouldNotBeNull().id
    rig.state.addListener(rig.recorder)

    rig.settleBranch()

    rig.recorder.events shouldContainExactly listOf("applied $id", "finished $id Looked")
  }

  "5b. a throwing listener does not keep the others from being called; logged by class only" {
    val rig = StateRig()
    rig.state.addListener(object : CurrentBranchListener {
      override fun onRefreshStarted(id: Long, soft: Boolean) = throw IllegalArgumentException(GIT_DIR)
    })
    rig.started()

    rig.recorder.events shouldContainExactly listOf("started 1 soft=true")
    rig.logs.single() shouldBe "Current-branch listener failed: java.lang.IllegalArgumentException"
  }

  "5c. an unregistered listener is no longer called" {
    val rig = StateRig()
    val registration = rig.state.addListener(rig.recorder)
    registration.unregister()

    rig.state.start()
    rig.settleBranch()

    rig.recorder.events.shouldBeEmpty()
  }

  "6a. editor activation outside the tracked work tree requests a BRANCH refresh; inside does nothing" {
    val rig = StateRig().started()
    rig.settleBranch()

    rig.editorListeners.single()(File("/home/alice/secret-repo/src/Other.kt"))
    rig.ui.drain()
    rig.fetch.calls shouldHaveSize 1

    rig.editorFile = File("/home/alice/elsewhere/B.kt")
    rig.editorListeners.single()(File("/home/alice/elsewhere/B.kt"))
    rig.ui.drain()
    rig.fetch.calls shouldHaveSize 2
    rig.fetch.calls.last().editorFile shouldBe File("/home/alice/elsewhere/B.kt")
  }

  "6b. the editor file is canonicalised before the rule: a symlinked path into the work tree does not refresh" {
    val rig = StateRig().started()
    rig.settleBranch()

    rig.editorListeners.single()(File("/link/secret-repo/src/Main.kt"))
    rig.ui.drain()

    rig.fetch.calls shouldHaveSize 1
  }

  "6c. an activation without a file does nothing" {
    val rig = StateRig().started()
    rig.settleBranch()

    rig.editorListeners.single()(null)
    rig.ui.drain()

    rig.fetch.calls shouldHaveSize 1
  }

  "7a. dispose stops the refresher, removes the editor listener and cancels the fetch; late hops reach nobody" {
    val rig = StateRig().started()
    val call = rig.fetch.calls.single()

    rig.state.dispose()
    call.cancelled shouldBe true
    call.apply(GIT_DIR) { resolvedBranch() }
    rig.ui.drain()

    rig.activity.unregistered shouldBe 1
    rig.refs.unregistered shouldBe 1
    rig.editorUnregistered shouldBe 1
    rig.recorder.events shouldContainExactly listOf("started 1 soft=true")
    rig.state.snapshot.input.shouldBeNull()
    rig.state.isDisposed shouldBe true
  }

  "7b. dispose is idempotent, and after it nothing fetches or registers" {
    val rig = StateRig().started()
    rig.state.dispose()
    rig.state.dispose()

    rig.state.refreshFull()
    rig.state.requestRefresh(RefreshReason.MANUAL)
    rig.state.start()
    rig.time.advanceBy(PERIOD)
    rig.activity.set(false)
    rig.activity.set(true)
    rig.editorListeners.single()(File("/elsewhere/X.kt"))
    rig.ui.drain()
    val late = StateRecorder()
    rig.state.addListener(late).unregister()

    rig.fetch.calls shouldHaveSize 1
    rig.activity.unregistered shouldBe 1
    rig.editorUnregistered shouldBe 1
    late.events.shouldBeEmpty()
  }

  "7c. dispose before start leaves the entry inert" {
    val rig = StateRig()
    rig.state.dispose()
    rig.state.start()

    rig.fetch.calls.shouldBeEmpty()
    rig.activity.registered shouldBe 0
  }

  "8. two windows are isolated: each fetches with its own editor file and keeps its own snapshot" {
    val a = StateRig(editor = File("/a/src/A.kt")).started()
    val b = StateRig(editor = File("/b/src/B.kt")).started()

    a.fetch.calls.single().editorFile shouldBe File("/a/src/A.kt")
    b.fetch.calls.single().editorFile shouldBe File("/b/src/B.kt")
    a.applyLast(resolvedBranch(), gitDir = "/a/.git")
    b.applyLast(NoRepository, gitDir = null)
    a.ui.drain()
    b.ui.drain()

    a.state.snapshot.gitDir shouldBe "/a/.git"
    b.state.snapshot.input shouldBe NoRepository
  }

  "9. nothing the entry logs or prints carries a path, branch or editor file" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.state.refreshFull()
    rig.fetch.calls.last().fail(IllegalStateException(GIT_DIR))
    rig.ui.drain()

    (rig.logs + rig.state.snapshot.toString() + rig.state.toString()).forEach { line ->
      listOf("secret", "/home/alice", "Main.kt").forEach { line shouldNotContain it }
    }
  }

  "R6: a coalesced refresh started by a settle is announced after that settle's finish" {
    val rig = StateRig().started()
    rig.state.requestRefresh(RefreshReason.BRANCH)
    rig.settleBranch()
    rig.recorder.events shouldContainExactly listOf(
      "started 1 soft=true",
      "applied 1",
      "finished 1 Looked",
      "started 2 soft=true",
    )
  }

  "a finish for a repository the snapshot does not show leaves work tree and head kind alone" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)
    rig.fetch.calls.last().finish(TrackedHead("/other/.git", HeadKey.Detached("abc"), "/other"))
    rig.ui.drain()
    rig.state.snapshot.gitDir shouldBe GIT_DIR
    rig.state.snapshot.workTree shouldBe CANONICAL_WORK_TREE
    rig.state.snapshot.headKind shouldBe HeadKind.BRANCH
  }

  "refreshFull after a settle hands the fetcher no previous value (placeholders)" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.state.refreshFull()
    rig.fetch.calls.last().soft shouldBe false
    rig.fetch.calls.last().previous.shouldBeNull()
  }

  "T3b: an apply for a repository other than the reported head's stays UNKNOWN" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)
    rig.fetch.calls.last().onHead(TrackedHead("/other/.git", HeadKey.Detached("abc"), "/other", onSingleTag = true))
    rig.applyLast(Resolved(null, null), gitDir = "/third/.git")
    rig.ui.drain()
    rig.state.snapshot.headKind shouldBe HeadKind.UNKNOWN
    rig.state.snapshot.workTree.shouldBeNull()
  }

  "T3b: a superseded refresh's head report does not leak into the next refresh's repository switch" {
    val rig = StateRig().started()
    rig.settleBranch()
    rig.time.advanceBy(PERIOD)
    rig.fetch.calls.last().onHead(TrackedHead("/other/.git", HeadKey.Detached("abc"), "/other", onSingleTag = true))
    rig.ui.drain() // recorded for refresh 2, not applied (snapshot still on GIT_DIR)
    rig.state.refreshFull()
    rig.applyLast(Resolved(null, null), gitDir = "/other/.git") // refresh 3's placeholder, before its own report
    rig.ui.drain()
    rig.state.snapshot.headKind shouldBe HeadKind.UNKNOWN
    rig.state.snapshot.workTree.shouldBeNull()
  }
})
