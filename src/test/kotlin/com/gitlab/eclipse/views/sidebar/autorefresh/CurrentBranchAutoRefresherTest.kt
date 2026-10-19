package com.gitlab.eclipse.views.sidebar.autorefresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain

private const val PERIOD = 30_000L
private const val REPO = "/home/alice/work/secret-project/.git"
private const val SHA = "0123456789abcdef0123456789abcdef01234567"
private val MAIN = HeadKey.Branch("feature/secret-branch")

/** Every line any [Rig] logged in this spec; test 10 checks them all (a StringSpec runs its tests in order, test 10 last). */
private val allLogs = mutableListOf<String>()

/** One refresher wired to fakes; every test drives it through these. */
private class Rig(active: Boolean = true) {
  val time = VirtualTime()
  val activity = FakeActivity(active)
  val refs = FakeRefs()
  val probe = FakeProbe()
  val port = FakePort()
  val logs = mutableListOf<String>()
  val refresher = CurrentBranchAutoRefresher(time, time, activity, refs, probe, port, {
    logs += it
    allLogs += it
  })

  /** Settles the latest soft refresh the port received. */
  fun settleLast(head: TrackedHead? = TrackedHead(REPO, MAIN)) = refresher.onRefreshSettled(port.tickets.last(), head)

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

    rig.refresher.onRefreshSettled(manual, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 2
    rig.logs.last() shouldEndWith "COALESCED"
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
    rig.refresher.onRefreshSettled(soft, TrackedHead(REPO, MAIN))
    rig.port.tickets shouldHaveSize 2
    rig.time.pendingCount shouldBe 0

    rig.refresher.onRefreshSettled(manual, TrackedHead(REPO, MAIN))
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

  "7b. a settle without a repository (null head) keeps the previously tracked head" {
    val rig = Rig().tracking()
    rig.time.advanceBy(PERIOD)
    rig.settleLast(null)
    rig.refs.fire(REPO)
    rig.time.advanceBy(300)
    rig.probe.requests.map { it.first } shouldBe listOf(REPO)
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
    rig.refresher.onRefreshSettled(inFlight, TrackedHead(REPO, MAIN))
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

  "10. what the auto refresh logs or prints names only event types and exception classes, never a path, branch or SHA" {
    allLogs.isEmpty() shouldBe false
    allLogs.forEach { line ->
      line shouldMatch Regex("^[A-Za-z -]+: (TIMER|FOCUS|BRANCH|MANUAL|COALESCED|[A-Za-z0-9_.$]+)$")
      listOf(REPO, "secret", SHA, "feature/").forEach { line shouldNotContain it }
    }

    val printed = listOf(MAIN, HeadKey.Detached(SHA), HeadKey.Unavailable, TrackedHead(REPO, MAIN)).joinToString()
    printed shouldNotContain "secret"
    printed shouldNotContain SHA
    printed shouldNotContain REPO
  }
})
