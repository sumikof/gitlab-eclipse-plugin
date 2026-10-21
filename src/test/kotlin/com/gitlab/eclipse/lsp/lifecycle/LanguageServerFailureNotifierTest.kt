package com.gitlab.eclipse.lsp.lifecycle

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException

class LanguageServerFailureNotifierTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun state(
    phase: LanguageServerPhase,
    generation: Long = 1,
    origin: StartOrigin? = StartOrigin.STARTUP,
    failure: LanguageServerFailureKind? = null,
  ) = LanguageServerLifecycleState(phase, generation, origin, failure)

  fun failedEdge(origin: StartOrigin, kind: LanguageServerFailureKind, generation: Long = 1) =
    state(LanguageServerPhase.STARTING, generation, origin) to
      state(LanguageServerPhase.FAILED, generation, origin, kind)

  class Fixture(
    var workbench: Boolean = true,
    val store: LanguageServerLifecycleStore = LanguageServerLifecycleStore(),
  ) {
    val queued = mutableListOf<Runnable>()
    val presented = mutableListOf<LanguageServerFailureKind>()
    val closed = mutableListOf<LanguageServerFailureKind>()
    val notifier = LanguageServerFailureNotifier(
      store = store,
      uiDispatch = { queued += it },
      workbenchAvailable = { workbench },
      present = { kind ->
        presented += kind
        AutoCloseable { closed += kind }
      },
    )

    init {
      store.addListener(notifier)
    }

    fun runUi() {
      while (queued.isNotEmpty()) queued.removeAt(0).run()
    }
  }

  describe("shouldNotify") {
    it("is true for every failure kind of a startup attempt") {
      LanguageServerFailureKind.entries.forEach { kind ->
        val (old, new) = failedEdge(StartOrigin.STARTUP, kind)
        shouldNotify(old, new) shouldBe true
      }
    }

    it("is true for a crash after a restart") {
      val old = state(LanguageServerPhase.RUNNING, origin = StartOrigin.RESTART)
      val new = state(
        LanguageServerPhase.FAILED,
        origin = StartOrigin.RESTART,
        failure = LanguageServerFailureKind.CRASHED,
      )
      shouldNotify(old, new) shouldBe true
    }

    it("is false for any other failure of a restart attempt (the handler toast covers it)") {
      LanguageServerFailureKind.entries.filter { it != LanguageServerFailureKind.CRASHED }.forEach { kind ->
        val (old, new) = failedEdge(StartOrigin.RESTART, kind)
        shouldNotify(old, new) shouldBe false
      }
    }

    it("is false when the transition is not a FAILED edge") {
      val failed = state(LanguageServerPhase.FAILED, failure = LanguageServerFailureKind.CRASHED)
      shouldNotify(failed, failed) shouldBe false
      shouldNotify(state(LanguageServerPhase.STARTING), state(LanguageServerPhase.RUNNING)) shouldBe false
      shouldNotify(failed, state(LanguageServerPhase.STOPPED, origin = null)) shouldBe false
    }

    it("is false across generations") {
      val old = state(LanguageServerPhase.RUNNING, generation = 1)
      val new = state(LanguageServerPhase.FAILED, generation = 2, failure = LanguageServerFailureKind.START_FAILED)
      shouldNotify(old, new) shouldBe false
    }
  }

  describe("failureMessage") {
    it("uses the VS Code text for startup failures and an Eclipse text for crashes") {
      LanguageServerFailureKind.entries.filter { it != LanguageServerFailureKind.CRASHED }.forEach { kind ->
        failureMessage(kind) shouldBe "GitLab Language Server failed to start"
      }
      failureMessage(LanguageServerFailureKind.CRASHED) shouldBe "GitLab Language Server stopped unexpectedly"
    }
  }

  describe("onTransition") {
    it("dispatches to the UI and presents the failure only when the runnable runs") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.STARTUP)
      f.store.markFailed(g, LanguageServerFailureKind.INSTALL_FAILED)

      f.queued.size shouldBe 1
      f.presented shouldBe emptyList()
      f.runUi()
      f.presented shouldBe listOf(LanguageServerFailureKind.INSTALL_FAILED)
    }

    it("presents a crash after a restart") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.RESTART)
      f.store.markRunning(g)
      f.store.exitObserved(g)
      f.runUi()
      f.presented shouldBe listOf(LanguageServerFailureKind.CRASHED)
    }

    it("presents nothing for a failed restart attempt") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.RESTART)
      f.store.markFailed(g, LanguageServerFailureKind.INITIALIZE_TIMEOUT)
      f.runUi()
      f.presented shouldBe emptyList()
    }

    it("re-reads the snapshot: no popup once the state has moved on") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.STARTUP)
      f.store.markFailed(g, LanguageServerFailureKind.START_FAILED)
      f.store.markStopped(g)
      f.runUi()
      f.presented shouldBe emptyList()
    }

    it("re-reads the snapshot: no popup once a newer generation started") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.STARTUP)
      f.store.markFailed(g, LanguageServerFailureKind.START_FAILED)
      val g2 = f.store.beginAttempt(StartOrigin.RESTART)
      f.store.markFailed(g2, LanguageServerFailureKind.START_FAILED)
      f.runUi()
      f.presented shouldBe emptyList()
    }

    it("presents nothing once the store is closed") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.STARTUP)
      f.store.markFailed(g, LanguageServerFailureKind.START_FAILED)
      f.store.close()
      f.runUi()
      f.presented shouldBe emptyList()
    }

    it("presents nothing when the workbench is not available") {
      val f = Fixture(workbench = false)
      val g = f.store.beginAttempt(StartOrigin.STARTUP)
      f.store.markFailed(g, LanguageServerFailureKind.START_FAILED)
      f.runUi()
      f.presented shouldBe emptyList()
    }

    it("closes the popup once the state leaves that generation's FAILED") {
      val f = Fixture()
      val g = f.store.beginAttempt(StartOrigin.STARTUP)
      f.store.markFailed(g, LanguageServerFailureKind.START_FAILED)
      f.runUi()
      f.closed shouldBe emptyList()

      f.store.markStopped(g)
      f.runUi()
      f.closed shouldBe listOf(LanguageServerFailureKind.START_FAILED)

      // Idempotent: a later transition does not close it again.
      f.store.beginAttempt(StartOrigin.RESTART)
      f.runUi()
      f.closed shouldBe listOf(LanguageServerFailureKind.START_FAILED)
    }

    it("never throws when the UI dispatch fails (display disposed or workbench gone)") {
      val store = LanguageServerLifecycleStore()
      listOf<() -> Nothing>(
        { throw SWTException(SWT.ERROR_DEVICE_DISPOSED) },
        { error("Workbench has not been created yet.") },
      ).forEach { failure ->
        val notifier = LanguageServerFailureNotifier(
          store = store,
          uiDispatch = { failure() },
          workbenchAvailable = { true },
          present = { null },
        )
        val (old, new) = failedEdge(StartOrigin.STARTUP, LanguageServerFailureKind.START_FAILED)
        shouldNotThrowAny { notifier.onTransition(old, new) }
      }
    }
  }
})
