package com.gitlab.eclipse.lsp.lifecycle

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanguageServerLifecycleStoreTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun running(store: LanguageServerLifecycleStore): Long =
    store.beginAttempt(StartOrigin.STARTUP).also { store.markRunning(it) }

  describe("LanguageServerLifecycleStore") {
    it("starts in the initial state") {
      val store = LanguageServerLifecycleStore()
      store.snapshot() shouldBe LanguageServerLifecycleState.INITIAL
      store.isClosed shouldBe false
    }

    it("beginAttempt increments the generation and sets STARTING with the origin") {
      val store = LanguageServerLifecycleStore()
      store.beginAttempt(StartOrigin.STARTUP) shouldBe 1L
      store.snapshot() shouldBe
        LanguageServerLifecycleState(LanguageServerPhase.STARTING, 1, StartOrigin.STARTUP, null)
      store.beginAttempt(StartOrigin.RESTART) shouldBe 2L
      store.beginAttempt(StartOrigin.RESTART) shouldBe 3L
      store.snapshot().origin shouldBe StartOrigin.RESTART
    }

    it("markRunning accepts the current generation and rejects a stale one") {
      val store = LanguageServerLifecycleStore()
      val g1 = store.beginAttempt(StartOrigin.STARTUP)
      val g2 = store.beginAttempt(StartOrigin.RESTART)
      store.markRunning(g1) shouldBe false
      store.markRunning(g2) shouldBe true
      store.snapshot().phase shouldBe LanguageServerPhase.RUNNING
      store.markRunning(g2) shouldBe false
    }

    it("markRunning is rejected from FAILED and from STOPPED") {
      val store = LanguageServerLifecycleStore()
      val g = store.beginAttempt(StartOrigin.STARTUP)
      store.markFailed(g, LanguageServerFailureKind.INITIALIZE_TIMEOUT) shouldBe true
      store.markRunning(g) shouldBe false
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.INITIALIZE_TIMEOUT
      store.markStopped(g) shouldBe true
      store.markRunning(g) shouldBe false
      store.snapshot().phase shouldBe LanguageServerPhase.STOPPED
    }

    it("does not call a listener added after close") {
      val store = LanguageServerLifecycleStore()
      store.close()
      var calls = 0
      store.addListener { _, _ -> calls++ }
      store.beginAttempt(StartOrigin.STARTUP) shouldBe -1L
      calls shouldBe 0
    }

    it("markFailed first failure wins") {
      val store = LanguageServerLifecycleStore()
      val g = store.beginAttempt(StartOrigin.STARTUP)
      store.markFailed(g, LanguageServerFailureKind.START_FAILED) shouldBe true
      store.markFailed(g, LanguageServerFailureKind.INITIALIZE_FAILED) shouldBe false
      store.snapshot().failure shouldBe LanguageServerFailureKind.START_FAILED
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
    }

    it("markFailed is accepted from RUNNING and rejected from STOPPED") {
      val store = LanguageServerLifecycleStore()
      store.markFailed(0, LanguageServerFailureKind.CRASHED) shouldBe false
      val g = running(store)
      store.markFailed(g, LanguageServerFailureKind.CRASHED) shouldBe true
      val g2 = store.beginAttempt(StartOrigin.STARTUP)
      store.markStopped(g2) shouldBe true
      store.markFailed(g2, LanguageServerFailureKind.START_FAILED) shouldBe false
    }

    it("exitObserved maps STARTING to EXITED_DURING_STARTUP and RUNNING to CRASHED") {
      val store = LanguageServerLifecycleStore()
      val g1 = store.beginAttempt(StartOrigin.STARTUP)
      store.exitObserved(g1) shouldBe true
      store.snapshot().failure shouldBe LanguageServerFailureKind.EXITED_DURING_STARTUP
      val g2 = running(store)
      store.exitObserved(g2) shouldBe true
      store.snapshot().failure shouldBe LanguageServerFailureKind.CRASHED
    }

    it("exitObserved is ignored when FAILED, STOPPED or stale") {
      val store = LanguageServerLifecycleStore()
      val g1 = store.beginAttempt(StartOrigin.STARTUP)
      store.markFailed(g1, LanguageServerFailureKind.START_FAILED)
      store.exitObserved(g1) shouldBe false
      store.snapshot().failure shouldBe LanguageServerFailureKind.START_FAILED
      val g2 = store.beginAttempt(StartOrigin.RESTART)
      store.exitObserved(g1) shouldBe false
      store.markStopped(g2)
      store.exitObserved(g2) shouldBe false
    }

    it("markStopped clears the failure") {
      val store = LanguageServerLifecycleStore()
      val g = store.beginAttempt(StartOrigin.STARTUP)
      store.markFailed(g, LanguageServerFailureKind.INSTALL_FAILED)
      store.markStopped(g) shouldBe true
      store.snapshot() shouldBe LanguageServerLifecycleState(LanguageServerPhase.STOPPED, g, null, null)
      store.markStopped(g - 1) shouldBe false
    }

    it("after close every mutator is false, beginAttempt is -1 and listeners are not called") {
      val store = LanguageServerLifecycleStore()
      val g = store.beginAttempt(StartOrigin.STARTUP)
      var calls = 0
      store.addListener { _, _ -> calls++ }
      store.close()
      store.close()
      store.isClosed shouldBe true
      store.beginAttempt(StartOrigin.RESTART) shouldBe -1L
      store.markRunning(g) shouldBe false
      store.markFailed(g, LanguageServerFailureKind.START_FAILED) shouldBe false
      store.exitObserved(g) shouldBe false
      store.markStopped(g) shouldBe false
      calls shouldBe 0
    }

    it("notifies listeners exactly once per applied transition with old and new") {
      val store = LanguageServerLifecycleStore()
      val seen = mutableListOf<Pair<LanguageServerLifecycleState, LanguageServerLifecycleState>>()
      store.addListener { o, n -> seen.add(o to n) }
      val g = store.beginAttempt(StartOrigin.STARTUP)
      store.markRunning(g)
      store.markRunning(g)
      store.markRunning(g + 5)
      seen.size shouldBe 2
      seen[0].first shouldBe LanguageServerLifecycleState.INITIAL
      seen[0].second.phase shouldBe LanguageServerPhase.STARTING
      seen[1].first.phase shouldBe LanguageServerPhase.STARTING
      seen[1].second.phase shouldBe LanguageServerPhase.RUNNING
    }

    it("contains a throwing listener and still calls the others") {
      val store = LanguageServerLifecycleStore()
      var second = 0
      store.addListener { _, _ -> error("boom") }
      store.addListener { _, _ -> second++ }
      store.beginAttempt(StartOrigin.STARTUP) shouldBe 1L
      second shouldBe 1
    }

    it("removes a listener when its handle is closed") {
      val store = LanguageServerLifecycleStore()
      var calls = 0
      val handle = store.addListener { _, _ -> calls++ }
      store.beginAttempt(StartOrigin.STARTUP)
      handle.close()
      store.beginAttempt(StartOrigin.RESTART)
      calls shouldBe 1
    }

    it("handles concurrent beginAttempt calls with increasing generations per thread") {
      val store = LanguageServerLifecycleStore()
      val threads = 8
      val perThread = 500
      val seen = ThreadLocal.withInitial { mutableListOf<Long>() }
      val all = CopyOnWriteArrayList<List<Long>>()
      store.addListener { _, n -> seen.get().add(n.generation) }
      val pool = Executors.newFixedThreadPool(threads)
      val start = CountDownLatch(1)
      val done = CountDownLatch(threads)
      repeat(threads) {
        pool.execute {
          start.await()
          repeat(perThread) { store.beginAttempt(StartOrigin.RESTART) }
          all.add(seen.get().toList())
          done.countDown()
        }
      }
      start.countDown()
      done.await(5, TimeUnit.SECONDS) shouldBe true
      pool.shutdown()
      store.snapshot().generation shouldBe (threads * perThread).toLong()
      all.size shouldBe threads
      all.forEach { list ->
        list.size shouldBe perThread
        list.zipWithNext().all { (a, b) -> b > a } shouldBe true
      }
    }
  }
})
