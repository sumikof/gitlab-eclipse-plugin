package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.ci.actions.InFlightWriteGuard
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteLauncher
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadEntry
import com.gitlab.eclipse.views.inlinethread.InlineThreadItem
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.InlineThreadState
import com.gitlab.eclipse.views.inlinethread.InlineThreadSurface
import com.gitlab.eclipse.views.inlinethread.NEW_THREAD_ID
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import com.gitlab.eclipse.views.inlinethread.draftsToPreserve
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.charset.StandardCharsets

private const val EPOCH = 7L
private const val BODY = "secret body text"
private val REF = MergeRequestRef("https://gitlab.example.com", "fp", 7L, 42L, "gid://gitlab/MergeRequest/1", "g/p")
private val IDENTITY = SessionIdentity("https://gitlab.example.com", "fp", 7L, 42L, "head1", "src/a.kt")
private val OTHER_REF = REF.copy(projectId = 9L, mrIid = 5L, mrGid = "gid://gitlab/MergeRequest/2")
private val OTHER_IDENTITY = IDENTITY.copy(projectId = 9L, mrIid = 5L, headSha = "head9")
private val SNAPSHOT = LineSnapshot(File("/tmp/a.kt"), 3, 10, "text\n", StandardCharsets.UTF_8)

private val SESSION = ReviewSessionSnapshot(
  identity = IDENTITY,
  mrRef = REF,
  baseSha = "base1",
  startSha = "start1",
  headSha = "head1",
  oldPath = "src/a.kt",
  newPath = "src/a.kt",
  lineMap = DiffLineMap.Identity,
  canCreateNote = true,
  placements = emptyList(),
)

private val DEFAULT_ACTIONS = setOf(InlineThreadAction.REPLY, InlineThreadAction.RESOLVE)

private fun item(id: String, actions: Set<InlineThreadAction> = DEFAULT_ACTIONS) =
  InlineThreadItem(
    threadId = id,
    title = "Thread $id",
    entries = listOf(InlineThreadEntry("alice", "2026-09-27", "body")),
    resolved = false,
    moreEntriesOnServer = false,
    actions = actions,
    inputPlaceholder = "Reply…",
  )

private val NEW_MODEL = InlineThreadModel(listOf(item(NEW_THREAD_ID, setOf(InlineThreadAction.CREATE))))
private val REPLY_MODEL = InlineThreadModel(listOf(item("d1"), item("d2")))

/** The popup as the host sees it: the real state plus a log of what the host asked the widgets to do. */
private class FakeSurface(model: InlineThreadModel) : InlineThreadSurface {
  override val state = InlineThreadState(model)
  override var isOpen = true
  val events = mutableListOf<String>()

  /** `canSubmit()` as each refresh saw it: what the real popup would render on the Send button. */
  val submittableAtRefresh = mutableListOf<Boolean>()
  override fun refresh() {
    events += "refresh(busy=${state.busy})"
    submittableAtRefresh += state.canSubmit()
  }
  override fun close() {
    isOpen = false
    events += "close"
  }
}

private class FakeWrites : MrThreadWrites {
  var outcome: DiscussionWriteOutcome = DiscussionWriteOutcome.Success
  var setTarget: Pair<SessionIdentity, MergeRequestRef>? = IDENTITY to REF
  val creates = mutableListOf<List<Any?>>()
  val replies = mutableListOf<List<Any?>>()
  val resolves = mutableListOf<List<Any?>>()

  override fun create(
    snapshot: LineSnapshot,
    session: ReviewSessionSnapshot?,
    target: AttemptTarget,
    body: String,
    startEpoch: Long,
  ): DiscussionWriteOutcome {
    creates += listOf(snapshot, session, body, startEpoch)
    target.value = setTarget
    return outcome
  }

  override fun reply(session: ReviewSessionSnapshot, replyId: String, body: String, startEpoch: Long): DiscussionWriteOutcome {
    replies += listOf(session, replyId, body, startEpoch)
    return outcome
  }

  override fun resolve(session: ReviewSessionSnapshot, replyId: String, resolved: Boolean, startEpoch: Long): DiscussionWriteOutcome {
    resolves += listOf(session, replyId, resolved, startEpoch)
    return outcome
  }
}

private class HostHarness(
  kind: MrPopupKind,
  sessionNow: () -> ReviewSessionSnapshot? = { null },
  /** When set, wired the way `MrThreadPopups.show` wires it: its flag gates attempts, onClosed disposes it. */
  tracker: NewThreadEditTracker? = null,
) {
  val writes = FakeWrites()
  val notifies = mutableListOf<String>()
  val retries = mutableListOf<Triple<String, String, (String) -> Unit>>()
  val copyTexts = mutableListOf<Pair<String, String>>()
  val launcherLogs = mutableListOf<String>()
  val hostLogs = mutableListOf<String>()
  val refreshed = mutableListOf<Pair<SessionIdentity, MergeRequestRef>>()
  val sidebarReloads = mutableListOf<SessionIdentity>()
  val preserved = mutableListOf<String>()
  val reloadOutcomes = mutableListOf<LoadOutcome>()
  var closedCalls = 0
  var runOnUi: (() -> Unit) -> Unit = { it() }
  var refreshSession: (SessionIdentity, MergeRequestRef) -> Unit = { i, r -> refreshed += i to r }
  var stale = false
  val staleChecks = mutableListOf<LineSnapshot>()
  val hostNotifies = mutableListOf<String>()
  var edited = false

  val host = MrThreadPopupHost(
    kind = kind,
    sessionNow = sessionNow,
    writes = writes,
    newLauncher = { reload ->
      DiscussionWriteLauncher(
        runInBackground = { it() },
        runOnUi = { it() },
        reload = { onOutcome ->
          reload { outcome ->
            reloadOutcomes += outcome
            onOutcome(outcome)
          }
        },
        notify = { notifies += it },
        promptRetry = { m, b, r -> retries += Triple(m, b, r) },
        promptCopyText = { m, b -> copyTexts += m to b },
        log = { launcherLogs += it },
        registryActive = { true },
        registryEpoch = { EPOCH },
      )
    },
    runOnUi = { block -> runOnUi(block) },
    currentEpoch = { EPOCH },
    refreshSession = { i, r -> refreshSession(i, r) },
    reloadSidebar = { sidebarReloads += it },
    preserveDraft = { preserved += it },
    log = { hostLogs += it },
    onPopupClosed = {
      tracker?.dispose()
      closedCalls++
    },
    newThreadStale = { snapshot ->
      staleChecks += snapshot
      stale
    },
    notify = { hostNotifies += it },
    newThreadEdited = { tracker?.edited ?: edited },
  )

  fun allLogs() = launcherLogs + hostLogs
}

private fun FakeSurface.type(threadId: String, text: String) {
  state.select(threadId) shouldBe true
  state.onEdit(threadId, text)
}

private fun FakeSurface.submit(): SubmitTicket = state.beginSubmit().shouldNotBeNull()

class MrThreadPopupHostTest : DescribeSpec({
  describe("new thread (create)") {
    it("runs the attempt with the frozen snapshot, the UI-thread session and the epoch, then closes on Success") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT), sessionNow = { SESSION })
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.writes.creates shouldContainExactly listOf(listOf(SNAPSHOT, SESSION, BODY, EPOCH))
      s.state.busy shouldBe false
      s.isOpen shouldBe false
      s.state.draft(NEW_THREAD_ID) shouldBe ""
      // busy is released before the success effect is applied (§9.3.1, A29)
      s.events shouldContainExactly listOf("refresh(busy=false)", "close")
    }

    it("establishes / refreshes the session from the attempt's target and asks the sidebar to reload on Success") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT))
      h.writes.setTarget = OTHER_IDENTITY to OTHER_REF
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.refreshed shouldContainExactly listOf(OTHER_IDENTITY to OTHER_REF)
      h.sidebarReloads shouldContainExactly listOf(OTHER_IDENTITY)
    }

    it("Ambiguous: reload reports Skipped, so the unconfirmed message and copy-text only; draft stays (A20, A24)") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT))
      h.writes.outcome = DiscussionWriteOutcome.Ambiguous(java.io.IOException("timeout"))
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.reloadOutcomes shouldContainExactly listOf(LoadOutcome.Skipped)
      h.copyTexts shouldContainExactly listOf(DiscussionWriteLauncher.AMBIGUOUS_UNCONFIRMED_MESSAGE to BODY)
      h.refreshed shouldContainExactly listOf(IDENTITY to REF)
      h.sidebarReloads shouldContainExactly listOf(IDENTITY)
      s.state.busy shouldBe false
      s.isOpen shouldBe true
      s.state.draft(NEW_THREAD_ID) shouldBe BODY
      // #96: the kept draft must not be one Send click away from a duplicate post.
      s.state.canSubmit() shouldBe false
      // Busy release and the lock land in the same UI turn: no refresh ever shows Send enabled.
      s.submittableAtRefresh.shouldNotBeEmpty()
      s.submittableAtRefresh shouldNotContain true
      s.state.beginSubmit().shouldBeNull()
    }

    it("Ambiguous with an empty target (failed before G6): neither establishes nor reloads, still Skipped") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT))
      h.writes.outcome = DiscussionWriteOutcome.Ambiguous(java.io.IOException("timeout"))
      h.writes.setTarget = null
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.reloadOutcomes shouldContainExactly listOf(LoadOutcome.Skipped)
      h.refreshed.shouldBeEmpty()
      h.sidebarReloads.shouldBeEmpty()
      h.copyTexts shouldHaveSize 1
      s.state.busy shouldBe false
    }

    it("a failing session refresh still reports Skipped, still reloads the sidebar, and logs the class name only") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT))
      h.refreshSession = { _, _ -> throw IllegalStateException(BODY) }
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.reloadOutcomes shouldContainExactly listOf(LoadOutcome.Skipped)
      h.sidebarReloads shouldContainExactly listOf(IDENTITY)
      h.hostLogs.single { "exceptionType=" in it } shouldContain "IllegalStateException"
      h.allLogs().forEach { it shouldNotContain BODY }
      s.isOpen shouldBe false
    }

    it("Rejected: copy-text with the gate's message, busy released, draft kept, no reload") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT))
      h.writes.outcome = DiscussionWriteOutcome.Rejected(LineCommentAttempt.FILE_MISMATCH_MESSAGE)
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.copyTexts shouldContainExactly listOf(LineCommentAttempt.FILE_MISMATCH_MESSAGE to BODY)
      h.reloadOutcomes.shouldBeEmpty()
      s.state.busy shouldBe false
      s.state.draft(NEW_THREAD_ID) shouldBe BODY
      s.isOpen shouldBe true
    }

    it("refuses to launch once the editor changed (Codex r4): busy released, draft kept, one notification") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT), sessionNow = { SESSION })
      h.stale = true
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.staleChecks shouldContainExactly listOf(SNAPSHOT)
      h.writes.creates.shouldBeEmpty()
      h.hostNotifies shouldContainExactly listOf(MrThreadPopupHost.FILE_CHANGED_MESSAGE)
      h.reloadOutcomes.shouldBeEmpty()
      s.state.busy shouldBe false
      s.state.draft(NEW_THREAD_ID) shouldBe BODY
      s.isOpen shouldBe true
      h.allLogs().forEach { it shouldNotContain BODY }
      // The same unedited draft can be sent again once the editor matches (no ticket consumed).
      h.stale = false
      h.host.onSubmit(s, s.submit())
      h.writes.creates shouldHaveSize 1
    }

    it("refuses the first attempt when the editor changed after the popup opened (Codex r5)") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT), sessionNow = { SESSION })
      h.edited = true
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.writes.creates.shouldBeEmpty()
      h.copyTexts shouldContainExactly listOf(MrThreadPopupHost.FILE_CHANGED_MESSAGE to BODY)
      h.retries.shouldBeEmpty()
      h.reloadOutcomes.shouldBeEmpty()
      s.state.busy shouldBe false
      s.state.draft(NEW_THREAD_ID) shouldBe BODY
      h.allLogs().forEach { it shouldNotContain BODY }
    }

    it("refuses a [Retry] after a Definite failure when the editor changed meanwhile (Codex r5)") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT), sessionNow = { SESSION })
      h.writes.outcome = DiscussionWriteOutcome.Definite(RuntimeException("no"))
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())
      val (_, _, onRetry) = h.retries.single()
      h.writes.creates shouldHaveSize 1

      h.edited = true
      h.writes.outcome = DiscussionWriteOutcome.Success
      onRetry(BODY)

      h.writes.creates shouldHaveSize 1
      h.copyTexts shouldContainExactly listOf(MrThreadPopupHost.FILE_CHANGED_MESSAGE to BODY)
      h.retries shouldHaveSize 1
      s.state.busy shouldBe false
      s.isOpen shouldBe true
      s.state.draft(NEW_THREAD_ID) shouldBe BODY
    }

    it("refuses a [Retry] once the popup was closed after a Definite failure (Codex r5, fail closed)") {
      val tracker = NewThreadEditTracker(org.eclipse.jface.text.Document("text\n")).also { it.install() }
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT), sessionNow = { SESSION }, tracker = tracker)
      h.writes.outcome = DiscussionWriteOutcome.Definite(RuntimeException("no"))
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())
      val (_, _, onRetry) = h.retries.single()
      h.host.onClosed()
      h.writes.outcome = DiscussionWriteOutcome.Success
      onRetry(BODY)

      h.writes.creates shouldHaveSize 1
      h.copyTexts shouldContainExactly listOf(MrThreadPopupHost.FILE_CHANGED_MESSAGE to BODY)
    }

    it("lets a [Retry] proceed when the editor is unchanged (Codex r5)") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT), sessionNow = { SESSION })
      h.writes.outcome = DiscussionWriteOutcome.Definite(RuntimeException("no"))
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())
      h.writes.outcome = DiscussionWriteOutcome.Success
      h.retries.single().third(BODY)

      h.writes.creates shouldHaveSize 2
      h.copyTexts.shouldBeEmpty()
      s.isOpen shouldBe false
    }

    it("does not check the editor for a reply (not line-anchored)") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      h.stale = true
      h.edited = true
      val s = FakeSurface(REPLY_MODEL)
      s.type("d1", BODY)

      h.host.onSubmit(s, s.submit())

      h.staleChecks.shouldBeEmpty()
      h.hostNotifies.shouldBeEmpty()
      h.writes.replies shouldHaveSize 1
    }

    it("refuses a NEW ticket on an existing-threads host without launching anything") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      val s = FakeSurface(NEW_MODEL)
      s.type(NEW_THREAD_ID, BODY)

      h.host.onSubmit(s, s.submit())

      h.writes.creates.shouldBeEmpty()
      h.writes.replies.shouldBeEmpty()
      s.state.busy shouldBe false
      s.state.draft(NEW_THREAD_ID) shouldBe BODY
    }
  }

  describe("reply") {
    it("sends the reply for the selected thread and clears only that thread's draft on Success (A24, A30)") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      val s = FakeSurface(REPLY_MODEL)
      s.type("d2", "other draft")
      s.type("d1", BODY)

      h.host.onSubmit(s, s.submit())

      h.writes.replies shouldContainExactly listOf(listOf(SESSION, "d1", BODY, EPOCH))
      s.state.draft("d1") shouldBe ""
      s.state.draft("d2") shouldBe "other draft"
      s.state.busy shouldBe false
      s.isOpen shouldBe true
      s.events shouldContainExactly listOf("refresh(busy=false)", "refresh(busy=false)")
      h.refreshed shouldContainExactly listOf(IDENTITY to REF)
      h.sidebarReloads shouldContainExactly listOf(IDENTITY)
    }

    it("keeps a draft edited after send was pressed (edit generation moved) even on Success") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      val s = FakeSurface(REPLY_MODEL)
      s.type("d1", BODY)
      // Edit while the write is in flight: simulate by editing from inside the UI hop ordering —
      // the state is UI-confined, so the edit is applied before the terminal's hops run.
      val ticket = s.submit()
      s.state.onEdit("d1", "$BODY plus more")

      h.host.onSubmit(s, ticket)

      s.state.draft("d1") shouldBe "$BODY plus more"
      s.state.busy shouldBe false
    }

    it("Definite: [Retry] with the body; a successful retry with an edited body still applies the effect (A26)") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      h.writes.outcome = DiscussionWriteOutcome.Definite(RuntimeException("no"))
      val s = FakeSurface(REPLY_MODEL)
      s.type("d1", BODY)

      h.host.onSubmit(s, s.submit())

      val (message, body, onRetry) = h.retries.single()
      message shouldBe DiscussionWriteLauncher.DEFINITE_MESSAGE
      body shouldBe BODY
      s.state.busy shouldBe false
      s.state.draft("d1") shouldBe BODY
      h.refreshed.shouldBeEmpty()

      h.writes.outcome = DiscussionWriteOutcome.Success
      onRetry("$BODY (edited in the dialog)")

      h.writes.replies.map { it[2] } shouldContainExactly listOf(BODY, "$BODY (edited in the dialog)")
      s.state.draft("d1") shouldBe ""
      s.state.busy shouldBe false
    }

    it("Ambiguous on a reply never re-sends either (A20, #96)") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      h.writes.outcome = DiscussionWriteOutcome.Ambiguous(java.io.IOException("timeout"))
      val s = FakeSurface(REPLY_MODEL)
      s.type("d1", BODY)

      h.host.onSubmit(s, s.submit())

      h.reloadOutcomes shouldContainExactly listOf(LoadOutcome.Skipped)
      h.copyTexts shouldHaveSize 1
      h.refreshed shouldContainExactly listOf(IDENTITY to REF)
      s.state.busy shouldBe false
      s.state.draft("d1") shouldBe BODY
      s.state.canSubmit() shouldBe false
      s.submittableAtRefresh shouldNotContain true
      s.state.beginSubmit().shouldBeNull()
      h.writes.replies shouldHaveSize 1
    }

    it("GateRejected and Aborted release busy and keep the draft (A29)") {
      listOf(DiscussionWriteOutcome.GateRejected, DiscussionWriteOutcome.Aborted).forEach { outcome ->
        val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
        h.writes.outcome = outcome
        val s = FakeSurface(REPLY_MODEL)
        s.type("d1", BODY)

        h.host.onSubmit(s, s.submit())

        s.state.busy shouldBe false
        s.state.draft("d1") shouldBe BODY
        s.isOpen shouldBe true
        h.refreshed.shouldBeEmpty()
      }
    }

    it("releases busy immediately when the launcher's in-flight guard rejects the launch (A29)") {
      val key = DiscussionWriteKey.forDiscussion(IDENTITY.instanceUrl, IDENTITY.authFingerprint, "d1")
      InFlightWriteGuard.tryAcquire(key) shouldBe true
      try {
        val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
        val s = FakeSurface(REPLY_MODEL)
        s.type("d1", BODY)

        h.host.onSubmit(s, s.submit())

        h.writes.replies.shouldBeEmpty()
        h.notifies shouldContainExactly listOf(DiscussionWriteLauncher.ALREADY_IN_PROGRESS_MESSAGE)
        s.state.busy shouldBe false
        s.state.draft("d1") shouldBe BODY
        s.events shouldContainExactly listOf("refresh(busy=false)")
      } finally {
        InFlightWriteGuard.release(key)
      }
    }

    it("swallows a failing UI hop with one class-name log line and still hands the outcome to the launcher") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      var hops = 0
      h.runOnUi = {
        hops++
        error(BODY)
      }
      val s = FakeSurface(REPLY_MODEL)
      s.type("d1", BODY)

      h.host.onSubmit(s, s.submit())

      hops shouldBe 2
      h.hostLogs.filter { "exceptionType=IllegalStateException" in it } shouldHaveSize 2
      h.allLogs().forEach { it shouldNotContain BODY }
      // The launcher's terminal ran (its own runOnUi is separate): Success → reload.
      h.refreshed shouldContainExactly listOf(IDENTITY to REF)
    }
  }

  describe("resolve / unresolve") {
    it("resolves the selected thread with the target state and reloads on Success, without touching busy or drafts") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      val s = FakeSurface(REPLY_MODEL)
      s.type("d2", BODY)

      h.host.onAction(s, InlineThreadAction.RESOLVE)
      h.host.onAction(s, InlineThreadAction.UNRESOLVE)

      h.writes.resolves shouldContainExactly listOf(listOf(SESSION, "d2", true, EPOCH), listOf(SESSION, "d2", false, EPOCH))
      s.state.busy shouldBe false
      s.state.draft("d2") shouldBe BODY
      h.refreshed shouldContainExactly listOf(IDENTITY to REF, IDENTITY to REF)
      h.sidebarReloads shouldHaveSize 2
    }

    it("a failed resolve is a plain notification (no text to preserve)") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      h.writes.outcome = DiscussionWriteOutcome.Definite(RuntimeException("no"))
      val s = FakeSurface(REPLY_MODEL)

      h.host.onAction(s, InlineThreadAction.RESOLVE)

      h.notifies shouldContainExactly listOf(DiscussionWriteLauncher.DEFINITE_MESSAGE)
      h.retries.shouldBeEmpty()
      h.copyTexts.shouldBeEmpty()
    }

    it("ignores resolve on a new-thread popup and REPLY / CREATE as actions") {
      val h = HostHarness(MrPopupKind.NewThread(SNAPSHOT))
      val s = FakeSurface(NEW_MODEL)
      h.host.onAction(s, InlineThreadAction.RESOLVE)
      h.host.onAction(s, InlineThreadAction.CREATE)
      h.writes.resolves.shouldBeEmpty()
      h.writes.creates.shouldBeEmpty()

      val h2 = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      h2.host.onAction(FakeSurface(REPLY_MODEL), InlineThreadAction.REPLY)
      h2.writes.replies.shouldBeEmpty()
      h2.writes.resolves.shouldBeEmpty()
    }
  }

  describe("closing") {
    it("preserves each unsent draft through the copy-text prompt and reports the close") {
      val h = HostHarness(MrPopupKind.ExistingThreads(SESSION))
      h.host.preserveDrafts(listOf("one", "two"))
      h.host.onClosed()
      h.preserved shouldContainExactly listOf("one", "two")
      h.closedCalls shouldBe 1
    }

    it("draftsToPreserve leaves out the in-flight body once (the launcher keeps it) but not an edited one") {
      val s = InlineThreadState(REPLY_MODEL)
      s.onEdit("d1", "sending")
      s.onEdit("d2", "typed later")
      val ticket = s.beginSubmit().shouldNotBeNull()

      s.draftsToPreserve(ticket) shouldContainExactly listOf("typed later")

      s.onEdit("d1", "sending and more")
      s.draftsToPreserve(ticket) shouldContainExactly listOf("sending and more", "typed later")

      s.onAttemptFinished(ticket)
      s.draftsToPreserve(null) shouldContainExactly listOf("sending and more", "typed later")
    }

    it("draftsToPreserve ignores a ticket when nothing is in flight") {
      val s = InlineThreadState(REPLY_MODEL)
      s.onEdit("d1", "sending")
      val ticket = s.beginSubmit().shouldNotBeNull()
      s.onLaunchRejected(ticket)
      s.draftsToPreserve(ticket) shouldContainExactly listOf("sending")
    }
  }
})
