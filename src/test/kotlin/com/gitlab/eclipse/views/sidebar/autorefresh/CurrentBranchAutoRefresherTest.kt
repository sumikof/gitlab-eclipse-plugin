package com.gitlab.eclipse.views.sidebar.autorefresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain

private const val PERIOD = 30_000L
private const val REPO = "/home/alice/work/secret-project/.git"
private const val SHA = "0123456789abcdef0123456789abcdef01234567"
private val MAIN = HeadKey.Branch("feature/secret-branch")

/** A settle that looked at [head]; null means no repository resolved. */
private fun CurrentBranchAutoRefresher.settle(ticket: Long, head: TrackedHead?) =
  onRefreshSettled(ticket, if (head == null) SettleOutcome.NoRepository else SettleOutcome.Looked(head))

/** One refresher wired to fakes; every test drives it through these. */
private class Rig(active: Boolean = true) {
  val time = VirtualTime()
  val activity = FakeActivity(active)
  val refs = FakeRefs()
  val probe = FakeProbe()
  val port = FakePort()
  val logs = mutableListOf<String>()
  val warnings = mutableListOf<String>()
  val debugLines = mutableListOf<String>()
  val refresher = CurrentBranchAutoRefresher(
    time,
    time,
    activity,
    refs,
    probe,
    port,
    log = {
      logs += it
      warnings += it
    },
    debugLog = {
      logs += it
      debugLines += it
    },
  )

  /** Settles the latest soft refresh the port received. */
  fun settleLast(head: TrackedHead? = TrackedHead(REPO, MAIN)) = refresher.settle(port.tickets.last(), head)

  /** Started, one TIMER refresh settled on [REPO] at [MAIN], then left with the given activity. */
  fun tracking(stayActive: Boolean = true): Rig = apply {
    refresher.start()
    time.advanceBy(PERIOD)
    settleLast()
    activity.set(stayActive)
  }
}

class CurrentBranchAutoRefresherTest : StringSpec({
  "1. while active, a TIMER refresh fires 30 s after the previous settle, not a millisecond sooner" {
    val rig = Rig()
    rig.refresher.start()
    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 1
    rig.logs.last() shouldEndWith "TIMER"

    rig.settleLast()
    rig.time.advanceBy(PERIOD - 1)
    rig.port.tickets shouldHaveSize 1
    rig.time.advanceBy(1)
    rig.port.tickets shouldHaveSize 2

    rig.settleLast()
    rig.time.advanceBy(PERIOD - 1)
    rig.port.tickets shouldHaveSize 2
    rig.time.advanceBy(1)
    rig.port.tickets shouldHaveSize 3
    rig.time.pendingCount shouldBe 0
  }

  "2. while inactive no periodic timer stays scheduled and nothing refreshes, whether inactive from the start or after losing focus" {
    val fromStart = Rig(active = false)
    fromStart.refresher.start()
    fromStart.time.pendingCount shouldBe 0
    fromStart.time.advanceBy(10 * PERIOD)
    fromStart.port.tickets shouldHaveSize 0

    val lostFocus = Rig()
    lostFocus.refresher.start()
    lostFocus.activity.set(false)
    lostFocus.time.pendingCount shouldBe 0
    lostFocus.time.advanceBy(10 * PERIOD)
    lostFocus.port.tickets shouldHaveSize 0
  }

  "2b. a refresh settling while inactive does not arm the periodic timer" {
    val rig = Rig()
    rig.refresher.start()
    rig.time.advanceBy(PERIOD)
    rig.activity.set(false)
    rig.settleLast()
    rig.time.pendingCount shouldBe 0
    rig.time.advanceBy(10 * PERIOD)
    rig.port.tickets shouldHaveSize 1
  }

  "3a. regaining focus 30 s or more after the last settle refreshes once at once (FOCUS), probes HEAD once, then keeps one periodic timer" {
    val rig = Rig()
    rig.refresher.start()
    rig.time.advanceBy(PERIOD)
    rig.settleLast()
    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)

    rig.activity.set(true)
    rig.port.tickets shouldHaveSize 2
    rig.logs.last() shouldEndWith "FOCUS"
    rig.probe.requests.map { it.first } shouldBe listOf(REPO)

    rig.probe.answer(MAIN)
    rig.settleLast()
    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 1
  }

  "3b. regaining focus less than 30 s after the last settle does not refresh at once; the timer fires at settle + 30 s" {
    val rig = Rig()
    rig.refresher.start()
    rig.time.advanceBy(PERIOD)
    rig.settleLast()
    rig.time.advanceBy(5_000)
    rig.activity.set(false)
    rig.time.advanceBy(5_000)

    rig.activity.set(true)
    rig.port.tickets shouldHaveSize 1
    rig.probe.requests shouldHaveSize 1
    rig.probe.answer(MAIN)
    rig.time.pendingCount shouldBe 1
    rig.time.advanceBy(PERIOD - 10_000 - 1)
    rig.port.tickets shouldHaveSize 1
    rig.time.advanceBy(1)
    rig.port.tickets shouldHaveSize 2
    rig.logs.last() shouldEndWith "TIMER"
  }

  "3c. regaining focus before any refresh has settled refreshes at once" {
    val rig = Rig(active = false)
    rig.refresher.start()
    rig.activity.set(true)
    rig.port.tickets shouldHaveSize 1
    rig.logs.last() shouldEndWith "FOCUS"
  }

  "4a. a refs event on the tracked repository probes HEAD after 300 ms and a changed branch refreshes once (BRANCH), even while inactive" {
    val rig = Rig().tracking(stayActive = false)
    rig.refs.fire(REPO)
    rig.time.advanceBy(299)
    rig.probe.requests shouldHaveSize 0
    rig.time.advanceBy(1)
    rig.probe.requests.map { it.first } shouldBe listOf(REPO)

    rig.probe.answer(HeadKey.Branch("other-secret-branch"))
    rig.port.tickets shouldHaveSize 2
    rig.logs.last() shouldEndWith "BRANCH"
  }

  "4b. a refs event whose HEAD is unchanged does not refresh" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(MAIN)
    rig.port.tickets shouldHaveSize 1
  }

  "4c. a refs event on another repository is not probed" {
    val rig = Rig().tracking()
    rig.refs.fire("/home/alice/other/.git")
    rig.time.advanceBy(1_000)
    rig.probe.requests shouldHaveSize 0
  }

  "4d. ten refs events within 300 ms are probed once" {
    val rig = Rig().tracking()
    repeat(10) {
      rig.refs.fire(REPO)
      rig.time.advanceBy(29)
    }
    rig.time.advanceBy(300)
    rig.probe.requests shouldHaveSize 1
  }

  "4e. refs events while a probe is in flight lead to exactly one follow-up probe" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.requests shouldHaveSize 1
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.requests shouldHaveSize 1

    rig.probe.answer(MAIN)
    rig.probe.requests shouldHaveSize 2
    rig.probe.answer(MAIN)
    rig.probe.requests shouldHaveSize 2
    rig.port.tickets shouldHaveSize 1
  }

  "4f. a probe answer for a repository that is no longer tracked does not refresh" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.time.advanceBy(PERIOD)
    rig.settleLast(TrackedHead("/home/alice/other/.git", HeadKey.Branch("x")))
    rig.port.tickets shouldHaveSize 2

    rig.probe.answer(HeadKey.Branch("y"))
    rig.port.tickets shouldHaveSize 2
  }

  "5a. FOCUS and BRANCH requests during a soft refresh start nothing, then exactly one COALESCED refresh follows its settle" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 0

    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.activity.set(true)
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Branch("b3"))
    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 0

    rig.settleLast()
    rig.port.tickets shouldHaveSize 3
    rig.logs.last() shouldEndWith "COALESCED"
    rig.settleLast()
    rig.port.tickets shouldHaveSize 3
    rig.time.pendingCount shouldBe 1
  }

  "5b. requests during a manual refresh start nothing, then exactly one COALESCED refresh follows the manual settle" {
    val rig = Rig().tracking()
    val manual = rig.refresher.nextTicket()
    rig.refresher.onManualRefreshStarted(manual)
    rig.time.pendingCount shouldBe 0

    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.activity.set(true)
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.port.tickets shouldHaveSize 1

    rig.refresher.settle(manual, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 2
    rig.logs.last() shouldEndWith "COALESCED"
  }

  "5c. regaining focus 30 s or more after settle with a changed HEAD fetches once: the FOCUS refresh already reads the new HEAD" {
    val rig = Rig().tracking()
    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.activity.set(true)
    rig.port.tickets shouldHaveSize 2

    rig.probe.answer(HeadKey.Branch("checked-out-in-a-terminal"))
    rig.settleLast(TrackedHead(REPO, HeadKey.Branch("checked-out-in-a-terminal")))

    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 1
  }

  "5d. a HEAD change probed after the in-flight refresh started still coalesces into one more refresh" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.settleLast()
    rig.port.tickets shouldHaveSize 3
    rig.logs.last() shouldEndWith "COALESCED"
  }

  "5e. regaining focus less than 30 s after settle while a refresh runs arms no timer; its settle arms exactly one" {
    val rig = Rig().tracking()
    val manual = rig.refresher.nextTicket()
    rig.refresher.onManualRefreshStarted(manual)
    rig.activity.set(false)
    rig.time.advanceBy(5_000)
    rig.activity.set(true)
    rig.probe.answer(MAIN)
    rig.time.pendingCount shouldBe 0

    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 1
    rig.refresher.settle(manual, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 1
    rig.time.pendingCount shouldBe 1
  }

  "5f. becoming active while a refresh runs (the view turning visible during the first refresh) adds no FOCUS fetch" {
    val rig = Rig(active = false)
    rig.refresher.start()
    val manual = rig.refresher.nextTicket()
    rig.refresher.onManualRefreshStarted(manual)
    rig.activity.set(true)
    rig.refresher.settle(manual, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 0
    rig.time.pendingCount shouldBe 1
  }

  "6. a manual refresh supersedes the soft one: the soft settle is ignored, the manual settle re-arms the timer, pending is dropped" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    val soft = rig.port.tickets.last()
    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.activity.set(true)
    rig.probe.answer(MAIN)

    val manual = rig.refresher.nextTicket()
    rig.refresher.onManualRefreshStarted(manual)
    rig.refresher.settle(soft, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 0

    rig.refresher.settle(manual, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 1
    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 3
    rig.logs.last() shouldEndWith "TIMER"
  }

  "7. Branch -> Detached -> Unavailable -> Unavailable refreshes once, once, then not at all" {
    val rig = Rig().tracking()
    fun headChangesTo(key: HeadKey): Int {
      val before = rig.port.tickets.size
      rig.refs.fire(REPO)
      rig.time.advanceBy(300)
      rig.probe.answer(key)
      val started = rig.port.tickets.size - before
      if (started > 0) rig.settleLast(TrackedHead(REPO, key))
      return started
    }

    headChangesTo(HeadKey.Detached(SHA)) shouldBe 1
    headChangesTo(HeadKey.Unavailable) shouldBe 1
    headChangesTo(HeadKey.Unavailable) shouldBe 0
  }

  "7b. a settle without a repository (null head) stops tracking: refs events and regains probe nothing" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    rig.settleLast(null)
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.activity.set(false)
    rig.time.advanceBy(5_000)
    rig.activity.set(true)
    rig.probe.requests shouldHaveSize 0
    rig.port.tickets shouldHaveSize 2
  }

  "7e. a settle that learned nothing (cancelled or failed fetch) keeps tracking the repository" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    rig.refresher.onRefreshSettled(rig.port.tickets.last(), SettleOutcome.Unknown)
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.requests.map { it.first } shouldBe listOf(REPO)
    rig.time.pendingCount shouldBe 1
  }

  "7c. a tracked repository deleted (resolved but unreadable) refreshes once, then stays quiet" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Unavailable)
    rig.port.tickets shouldHaveSize 2
    rig.settleLast(TrackedHead(REPO, HeadKey.Unavailable))

    repeat(3) {
      rig.activity.set(false)
      rig.time.advanceBy(5_000)
      rig.activity.set(true)
      rig.probe.answer(HeadKey.Unavailable)
      rig.refs.fire(REPO)
      rig.time.advanceBy(300)
      rig.probe.answer(HeadKey.Unavailable)
    }
    rig.port.tickets shouldHaveSize 2
  }

  "7d. a tracked repository deleted (no longer resolved) refreshes once, then stays quiet" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Unavailable)
    rig.port.tickets shouldHaveSize 2
    rig.settleLast(null)

    repeat(3) {
      rig.activity.set(false)
      rig.time.advanceBy(5_000)
      rig.activity.set(true)
      rig.refs.fire(REPO)
      rig.time.advanceBy(300)
    }
    rig.probe.requests shouldHaveSize 1
    rig.port.tickets shouldHaveSize 2
  }

  "8a. stop() unregisters activity and refs once each and cancels the periodic and debounce timers" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.pendingCount shouldBe 2

    rig.refresher.stop()
    rig.activity.unregistered shouldBe 1
    rig.refs.unregistered shouldBe 1
    rig.time.pendingCount shouldBe 0
  }

  "8b. after stop() late activity, refs, probe, settle and timer callbacks start nothing and schedule nothing" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    val inFlight = rig.port.tickets.last()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.refs.fire(REPO)
    rig.time.loseCancels = true
    val scheduledBefore = rig.time.scheduledDelays.size

    rig.refresher.stop()
    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.activity.set(true)
    rig.refs.fire(REPO)
    rig.time.advanceBy(PERIOD)
    rig.probe.answer(HeadKey.Branch("late"))
    rig.refresher.settle(inFlight, TrackedHead(REPO, MAIN))
    rig.time.advanceBy(10 * PERIOD)

    rig.port.tickets shouldHaveSize 2
    rig.probe.requests shouldHaveSize 1
    rig.time.scheduledDelays shouldHaveSize scheduledBefore
  }

  "8c. a second stop() is a no-op" {
    val rig = Rig().tracking()
    rig.refresher.stop()
    rig.refresher.stop()
    rig.activity.unregistered shouldBe 1
    rig.refs.unregistered shouldBe 1
  }

  "8d. an unregister() that throws does not stop the other one and is logged by class name only" {
    val rig = Rig().tracking()
    rig.activity.failUnregister = IllegalStateException("cannot remove $REPO")
    rig.refresher.stop()
    rig.refs.unregistered shouldBe 1
    rig.logs.last() shouldEndWith "IllegalStateException"
    rig.logs.last() shouldNotContain REPO
  }

  "9a. a second start() registers nothing more and arms no second timer" {
    val rig = Rig()
    rig.refresher.start()
    rig.refresher.start()
    rig.activity.registered shouldBe 1
    rig.refs.registered shouldBe 1
    rig.time.pendingCount shouldBe 1
  }

  "9b. stop() then start() registers exactly one new pair, and the old listeners and probe stay inert" {
    val rig = Rig().tracking()
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.refresher.stop()
    rig.refresher.start()
    rig.activity.registered shouldBe 2
    rig.refs.registered shouldBe 2
    rig.activity.unregistered shouldBe 1
    rig.refs.unregistered shouldBe 1

    rig.probe.answer(HeadKey.Branch("from-the-old-session"))
    rig.port.tickets shouldHaveSize 1

    val scheduledBefore = rig.time.scheduledDelays.size
    rig.refs.fire(REPO)
    rig.time.scheduledDelays shouldHaveSize scheduledBefore + 1
    rig.time.advanceBy(300)
    rig.probe.requests shouldHaveSize 2
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.port.tickets shouldHaveSize 2
  }

  "8e. a soft refresh that fails to start does not stall the refresher: logged by class only, retried next period" {
    val rig = Rig().tracking()
    rig.port.failStart = IllegalStateException("cannot start for $REPO")
    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 2
    rig.logs.last() shouldEndWith "IllegalStateException"
    rig.logs.last() shouldNotContain REPO
    rig.time.pendingCount shouldBe 1

    rig.port.failStart = null
    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 3
  }

  "8f. a soft refresh failing to start while inactive leaves no timer, and the next HEAD change still refreshes" {
    val rig = Rig().tracking(stayActive = false)
    rig.port.failStart = IllegalStateException("boom")
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.time.pendingCount shouldBe 0

    rig.port.failStart = null
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.port.tickets shouldHaveSize 3
  }

  "8g. a probe that throws is logged by class only and does not block later probes" {
    val rig = Rig().tracking()
    rig.probe.failProbe = IllegalStateException("cannot read $REPO")
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.logs.last() shouldEndWith "IllegalStateException"
    rig.logs.last() shouldNotContain REPO

    rig.probe.failProbe = null
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.requests shouldHaveSize 1
    rig.probe.answer(HeadKey.Branch("b2"))
    rig.port.tickets shouldHaveSize 2
  }

  "9c. a start() whose second registration throws rolls back the first, logs the class only and can be retried" {
    val rig = Rig()
    rig.refs.failRegister = IllegalStateException("display for $REPO is disposed")
    rig.refresher.start()
    rig.activity.unregistered shouldBe 1
    rig.logs.last() shouldEndWith "IllegalStateException"
    rig.logs.last() shouldNotContain REPO
    rig.time.pendingCount shouldBe 0

    rig.refs.failRegister = null
    rig.refresher.start()
    rig.activity.registered shouldBe 2
    rig.refs.registered shouldBe 1
    rig.time.pendingCount shouldBe 1
  }

  "8h. a periodic tick that still fires after losing focus (its cancel was lost) does not refresh" {
    val rig = Rig().tracking()
    rig.time.loseCancels = true
    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.port.tickets shouldHaveSize 1
  }

  "10b. the per-refresh reason goes to the debug log; failures go to the warning log" {
    val rig = Rig().tracking()
    rig.port.failStart = IllegalStateException("x")
    rig.time.advanceBy(PERIOD)
    rig.debugLines shouldContainExactly listOf("Current-branch auto refresh: TIMER", "Current-branch auto refresh: TIMER")
    rig.warnings shouldContainExactly listOf("Current-branch auto refresh failed to start: java.lang.IllegalStateException")
  }

  "10c. trackedGitDir() exposes the tracked repository for the refs adapter's off-thread match" {
    val rig = Rig()
    rig.refresher.trackedGitDir() shouldBe null
    rig.tracking()
    rig.refresher.trackedGitDir() shouldBe REPO
    rig.time.advanceBy(PERIOD)
    rig.settleLast(null)
    rig.refresher.trackedGitDir() shouldBe null
  }

  "10. every line the refresher logs names only an event type or an exception class, never a path, branch or SHA" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD) // TIMER
    rig.settleLast()
    rig.activity.set(false)
    rig.time.advanceBy(PERIOD)
    rig.activity.set(true) // FOCUS
    rig.probe.answer(MAIN)
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(HeadKey.Detached(SHA)) // pending
    rig.settleLast(TrackedHead(REPO, MAIN)) // COALESCED
    rig.settleLast(TrackedHead(REPO, HeadKey.Detached(SHA)))
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.answer(MAIN) // BRANCH
    rig.port.failStart = IllegalStateException("cannot start for $REPO on $SHA")
    rig.settleLast(TrackedHead(REPO, MAIN))
    rig.time.advanceBy(PERIOD) // failed start
    rig.probe.failProbe = IllegalStateException("cannot read $REPO")
    rig.refs.fire(REPO)
    rig.time.advanceBy(300) // failed probe
    rig.activity.failUnregister = IllegalStateException("feature/secret-branch")
    rig.refresher.stop() // failed teardown
    rig.refs.failRegister = IllegalArgumentException(REPO)
    rig.refresher.start() // failed start of listening

    rig.logs shouldContainExactly listOf(
      "Current-branch auto refresh: TIMER",
      "Current-branch auto refresh: TIMER",
      "Current-branch auto refresh: FOCUS",
      "Current-branch auto refresh: COALESCED",
      "Current-branch auto refresh: BRANCH",
      "Current-branch auto refresh: TIMER",
      "Current-branch auto refresh failed to start: java.lang.IllegalStateException",
      "Current-branch HEAD probe failed to start: java.lang.IllegalStateException",
      "Current-branch auto refresh teardown failed: java.lang.IllegalStateException",
      // The start() rollback unregisters the activity again, and that unregister still throws.
      "Current-branch auto refresh teardown failed: java.lang.IllegalStateException",
      "Current-branch auto refresh could not start listening: java.lang.IllegalArgumentException",
    )
    rig.logs.forEach { line ->
      line shouldMatch Regex("^[A-Za-z -]+: (TIMER|FOCUS|BRANCH|MANUAL|COALESCED|[A-Za-z0-9_.$]+)$")
      listOf(REPO, "secret", SHA, "feature/").forEach { line shouldNotContain it }
    }

    val printed = listOf(MAIN, HeadKey.Detached(SHA), HeadKey.Unavailable, TrackedHead(REPO, MAIN)).joinToString()
    printed shouldNotContain "secret"
    printed shouldNotContain SHA
    printed shouldNotContain REPO
  }
})
