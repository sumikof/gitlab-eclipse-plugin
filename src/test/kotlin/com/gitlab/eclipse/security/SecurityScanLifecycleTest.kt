package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex

private const val PATH_A = "/w/a.kt"
private const val CANCELLED_MESSAGE =
  "GitLab security scan: the scan was cancelled because the language server restarted. " +
    "Run the scan again."

/**
 * The ordering the whole feature's clean up rests on.
 *
 * Two of the calls in one five step sequence want *opposite* values of the same counter, and both
 * mistakes are silent: the wrong one for `cancelPending` leaks every pending command, and the wrong
 * one for `deleteMarkersNotInEpoch` deletes the live connection's markers. Each is pinned by a test
 * that fails when the argument is swapped for its neighbour.
 */
class SecurityScanLifecycleTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun epoch() = DiagnosticGenerationRegistry.currentEpoch

  fun reset() {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
    SecurityScanStatusReporter.resetForTest()
  }

  beforeEach { reset() }
  afterEach { reset() }

  /** A command scan that is out on the wire, waiting for an answer that is never going to come. */
  fun pendingCommand(path: String = PATH_A, connectionEpoch: Long = epoch()): Long {
    val id = CommandWaiters.add(path, connectionEpoch)
    id shouldNotBe null
    CommandWaiters.markDeadlineArmed(id!!, connectionEpoch)
    CommandWaiters.isDeadlineArmed(id) shouldBe true
    return id
  }

  describe("onServerStopped") {
    it("cancels the waiters of the connection that died, so the epoch has to be read before the stop") {
      val dead = epoch()
      val waiter = pendingCommand()
      val notified = mutableListOf<String>()
      val audited = mutableListOf<String>()

      SecurityScanLifecycle.onServerStopped(
        markerService = null,
        notify = { notified += it },
        audit = { audited += it },
      )

      // Cleared together with its armed flag. Handed the post-stop epoch instead, `clear` matches
      // nothing at all: the waiter would still be here and its command would never be told.
      CommandWaiters.isDeadlineArmed(waiter) shouldBe false
      epoch() shouldBe dead + 1
      notified shouldBe listOf(CANCELLED_MESSAGE)
      audited.size shouldBe 1
      audited.single() shouldContain "outcome=cancelled"
      audited.single() shouldContain "source=command"
    }

    it("removes the markers of every epoch except the one the registry advanced to") {
      val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
      val dead = epoch()

      SecurityScanLifecycle.onServerStopped(markerService, {}, {})

      val live = epoch()
      live shouldBe dead + 1
      // The post-stop value keeps what the next connection publishes. The pre-stop one inverts the
      // predicate: it would keep the dead connection's markers and delete the live one's.
      verify(exactly = 1) { markerService.deleteMarkersNotInEpoch(live) }
      verify(exactly = 0) { markerService.deleteMarkersNotInEpoch(dead) }
    }

    it("says nothing at all when no command was waiting") {
      val notified = mutableListOf<String>()
      val audited = mutableListOf<String>()

      SecurityScanLifecycle.onServerStopped(null, { notified += it }, { audited += it })

      notified shouldBe emptyList()
      audited shouldBe emptyList()
    }

    it("advances the epoch once when both stop routes run for one connection") {
      val before = epoch()

      SecurityScanLifecycle.onServerStopped(null, {}, {})
      val once = epoch()
      SecurityScanLifecycle.onServerStopped(null, {}, {})

      once shouldBe before + 1
      epoch() shouldBe once
    }

    it("advances the epoch again once the next connection has started") {
      val before = epoch()
      SecurityScanLifecycle.onServerStopped(null, {}, {})

      // What the language server provider does when it spawns the replacement process. Without it
      // the stop below is a silent no-op and the dead connection's markers are kept, not removed.
      DiagnosticGenerationRegistry.onServerStarted()
      SecurityScanLifecycle.onServerStopped(null, {}, {})

      epoch() shouldBe before + 2
    }

    it("never throws, whatever the marker service, the notifier and the audit do") {
      val markerService = mockk<DiagnosticMarkerService>()
      every { markerService.deleteMarkersNotInEpoch(any()) } throws IllegalStateException("workspace is closed")
      val dead = epoch()
      pendingCommand()

      shouldNotThrowAny {
        SecurityScanLifecycle.onServerStopped(
          markerService,
          notify = { error("no display") },
          audit = { error("no platform log") },
        )
      }

      // Not merely swallowed: the steps that could run, ran.
      epoch() shouldBe dead + 1
      verify { markerService.deleteMarkersNotInEpoch(dead + 1) }
    }
  }

  describe("onBundleStopping") {
    it("stops applying diagnostics, removes every marker and detaches the save trigger") {
      val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
      val saveListener = mockk<SecurityScanSaveListener>(relaxUnitFun = true)

      SecurityScanLifecycle.onBundleStopping(markerService, saveListener)

      DiagnosticGenerationRegistry.active shouldBe false
      verifyOrder {
        markerService.deleteAllMarkers()
        saveListener.uninstall()
      }
    }

    it("never throws and still detaches the save trigger when the workspace has already gone") {
      val markerService = mockk<DiagnosticMarkerService>()
      every { markerService.deleteAllMarkers() } throws IllegalStateException("workspace is closed")
      val saveListener = mockk<SecurityScanSaveListener>(relaxUnitFun = true)

      shouldNotThrowAny { SecurityScanLifecycle.onBundleStopping(markerService, saveListener) }

      // The listener is held by every document provider it attached to, and those outlive the
      // bundle: a marker clean up that failed must not be the reason it stays attached.
      verify { saveListener.uninstall() }
    }

    it("never throws when there is nothing left to reach") {
      shouldNotThrowAny { SecurityScanLifecycle.onBundleStopping(null, null) }
    }
  }

  describe("settings transitions") {
    fun settings(markerService: DiagnosticMarkerService) =
      SecurityScanSettings(CoroutineScope(Dispatchers.Unconfined), Mutex(), markerService)

    fun response(status: Int?) = SecurityScanResponse(filePath = PATH_A, status = status)

    it("suspends the source, drops the waiting commands and removes what was published up to the watermark") {
      val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
      val waiter = pendingCommand()
      DiagnosticGenerationRegistry.nextGeneration(PATH_A, epoch()) shouldNotBe null
      val watermark = DiagnosticGenerationRegistry.currentGenerationCounter()
      val seq = DiagnosticGenerationRegistry.nextSettingsSeq()
      val connection = epoch()

      settings(markerService).applyTransition(enabled = false, seq = seq)

      DiagnosticGenerationRegistry.isSuspended(SECURITY_SCAN_SOURCE) shouldBe true
      CommandWaiters.isDeadlineArmed(waiter) shouldBe false
      verify { markerService.deleteMarkersBySource(SECURITY_SCAN_SOURCE, watermark) }
      // A settings transition is not a connection event: moving the epoch would tell every request
      // in flight that its connection had died.
      epoch() shouldBe connection
    }

    it("does nothing destructive when a newer transition has already been issued") {
      val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
      val waiter = pendingCommand()
      val stale = DiagnosticGenerationRegistry.nextSettingsSeq()
      // The user pressed OK again before this one got as far as the outbound lock.
      DiagnosticGenerationRegistry.nextSettingsSeq()

      settings(markerService).applyTransition(enabled = false, seq = stale)

      CommandWaiters.isDeadlineArmed(waiter) shouldBe true
      verify(exactly = 0) { markerService.deleteMarkersBySource(any(), any()) }
    }

    it("ignores a transition that a newer one has already applied") {
      val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
      val older = DiagnosticGenerationRegistry.nextSettingsSeq()
      val newer = DiagnosticGenerationRegistry.nextSettingsSeq()

      settings(markerService).applyTransition(enabled = false, seq = newer)
      // Arrives late, and would otherwise switch the source back on behind the newer decision.
      settings(markerService).applyTransition(enabled = true, seq = older)

      DiagnosticGenerationRegistry.isSuspended(SECURITY_SCAN_SOURCE) shouldBe true
    }

    it("resumes the source and forgets the save suppression when scanning is switched back on") {
      val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
      val failure = response(500)
      // A save that failed twice on the same file: the repeat is suppressed.
      (SecurityScanStatusReporter.settle(PATH_A, failure, epoch()) as ResponseDecision.Report).notify shouldNotBe null
      (SecurityScanStatusReporter.settle(PATH_A, failure, epoch()) as ResponseDecision.Report).notify shouldBe null

      settings(markerService).applyTransition(enabled = false, seq = DiagnosticGenerationRegistry.nextSettingsSeq())
      settings(markerService).applyTransition(enabled = true, seq = DiagnosticGenerationRegistry.nextSettingsSeq())

      DiagnosticGenerationRegistry.isSuspended(SECURITY_SCAN_SOURCE) shouldBe false
      // Switched off and back on: whatever was known about this file was learned before, so the
      // next failure is news again.
      (SecurityScanStatusReporter.settle(PATH_A, failure, epoch()) as ResponseDecision.Report).notify shouldNotBe null
    }
  }
})
