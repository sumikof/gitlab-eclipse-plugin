package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.ci.actions.InFlightWriteGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException

private const val INSTANCE_URL = "https://gitlab.example.com"
private const val AUTH_FINGERPRINT = "fp-node"

/**
 * A distinct key per test: [InFlightWriteGuard] is a process-wide singleton shared with the CI
 * write paths, so tests never share a key and always release what they acquire manually.
 */
private fun keyFor(id: String) = DiscussionWriteKey.forNote(INSTANCE_URL, AUTH_FINGERPRINT, id)

/**
 * Asserts the launcher no longer holds [key] — and releases the probe acquisition itself, so an
 * assertion here can never leave the process-wide guard poisoned for other specs.
 */
private fun assertGuardReleased(key: DiscussionWriteKey) {
  val acquired = InFlightWriteGuard.tryAcquire(key)
  if (acquired) InFlightWriteGuard.release(key)
  acquired shouldBe true
}

/**
 * Throws a deliberately generic [RuntimeException]: the escaped-throwable tests must prove the
 * launcher classifies ANY non-cancellation throwable as Definite, and they assert the audit line
 * carries literally `exceptionType=RuntimeException` — a more specific subclass would change
 * that observable. Suppression is scoped to this helper only.
 */
@Suppress("TooGenericExceptionThrown")
private fun throwRuntime(message: String): Nothing = throw RuntimeException(message)

/**
 * Records every body **and every startEpoch** passed to `write`, and returns [results] in order,
 * repeating the last. The epochs are recorded because a `[Retry]` / `[Send again]` must send with
 * the epoch re-frozen at re-entry time, not the one the first attempt started with.
 */
private class WriteSpy(vararg results: DiscussionWriteOutcome) {
  val bodies = mutableListOf<String>()
  val epochs = mutableListOf<Long>()
  private val queue = ArrayDeque(results.toList())
  val fn: (String, Long) -> DiscussionWriteOutcome = { body, startEpoch ->
    bodies += body
    epochs += startEpoch
    if (queue.size > 1) queue.removeFirst() else queue.first()
  }
}

/**
 * Test double wiring for [DiscussionWriteLauncher], mirroring the [DiscussionsLoaderTest] idiom:
 * synchronous `runInBackground`/`runOnUi` fakes by default (deterministic ordering), the UI hop
 * deferrable into a queue so the registry can be mutated *between* the background completion and
 * the terminal — that is how the lifecycle guard gets exercised.
 */
private class LauncherHarness(deferUi: Boolean = false) {
  val pendingUi = ArrayDeque<() -> Unit>()

  val notifications = mutableListOf<String>()
  val logs = mutableListOf<String>()

  /** Each entry is `message to body` as the prompt received them. */
  val retryPrompts = mutableListOf<Pair<String, String>>()
  val sendAgainPrompts = mutableListOf<Pair<String, String>>()
  val copyTextPrompts = mutableListOf<Pair<String, String>>()
  var lastRetryCallback: ((String) -> Unit)? = null
    private set
  var lastSendAgainCallback: ((String) -> Unit)? = null
    private set

  var reloadCount = 0
    private set

  /**
   * When non-null, `reload` invokes its callback immediately (synchronously, as the real loader
   * does on the UI thread) with this outcome. When null, the callback is captured but never
   * invoked — the loader's documented contract exception during shutdown/disposal.
   */
  var reloadOutcome: LoadOutcome? = null

  /**
   * When non-null, the `runOnUi` **scheduling call itself** throws this instead of accepting the
   * task — what `Display.asyncExec` does once the display is disposed. The terminal body never
   * runs in that case, so its own handling cannot see the throwable.
   */
  var uiSchedulingFailure: Throwable? = null

  val launcher = DiscussionWriteLauncher(
    runInBackground = { task -> task() },
    runOnUi = { task ->
      uiSchedulingFailure?.let { throw it }
      if (deferUi) pendingUi += task else task()
    },
    reload = { onOutcome ->
      reloadCount++
      reloadOutcome?.let { onOutcome(it) }
    },
    notify = { notifications += it },
    promptRetry = { message, body, onRetry ->
      retryPrompts += message to body
      lastRetryCallback = onRetry
    },
    promptSendAgain = { message, body, onSendAgain ->
      sendAgainPrompts += message to body
      lastSendAgainCallback = onSendAgain
    },
    promptCopyText = { message, body -> copyTextPrompts += message to body },
    log = { logs += it },
  )

  fun launch(key: DiscussionWriteKey, body: String, write: (String, Long) -> DiscussionWriteOutcome) {
    launcher.launch(key, body, DiscussionGenerationRegistry.currentEpoch, write)
  }

  fun runPendingUi() {
    while (pendingUi.isNotEmpty()) pendingUi.removeFirst().invoke()
  }

  fun assertNoUiEffects() {
    notifications.shouldBeEmpty()
    reloadCount shouldBe 0
    retryPrompts.shouldBeEmpty()
    sendAgainPrompts.shouldBeEmpty()
    copyTextPrompts.shouldBeEmpty()
  }
}

class DiscussionWriteLauncherTest : DescribeSpec({

  // The registry is a process-wide singleton: reset it around every test so no epoch or
  // activation state leaks between tests.
  beforeEach { DiscussionGenerationRegistry.resetForTest() }
  afterEach { DiscussionGenerationRegistry.resetForTest() }

  describe("in-flight guard") {
    it("a held key rejects the launch: one notify, zero write calls, and the pre-held key stays held") {
      val key = keyFor("guard-held")
      InFlightWriteGuard.tryAcquire(key) shouldBe true
      try {
        val h = LauncherHarness()
        val write = WriteSpy(DiscussionWriteOutcome.Success)

        h.launch(key, "typed text", write.fn)

        h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.ALREADY_IN_PROGRESS_MESSAGE)
        write.bodies.shouldBeEmpty()
        // The launcher must not have released a key it never acquired: the original owner's
        // acquisition must still be in force.
        InFlightWriteGuard.tryAcquire(key) shouldBe false
      } finally {
        InFlightWriteGuard.release(key)
      }
    }

    it("after Success the key is released") {
      val key = keyFor("release-success")
      val h = LauncherHarness()

      h.launch(key, "b", WriteSpy(DiscussionWriteOutcome.Success).fn)

      assertGuardReleased(key)
    }

    it("after Definite the key is released") {
      val key = keyFor("release-definite")
      val h = LauncherHarness()

      h.launch(key, "b", WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException())).fn)

      assertGuardReleased(key)
    }

    it("after Ambiguous the key is released") {
      val key = keyFor("release-ambiguous")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied

      h.launch(key, "b", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      assertGuardReleased(key)
    }

    it("after GateRejected the key is released") {
      val key = keyFor("release-gate-rejected")
      val h = LauncherHarness()

      h.launch(key, "b", WriteSpy(DiscussionWriteOutcome.GateRejected).fn)

      assertGuardReleased(key)
    }

    it("after Aborted the key is released") {
      val key = keyFor("release-aborted")
      val h = LauncherHarness()

      h.launch(key, "b", WriteSpy(DiscussionWriteOutcome.Aborted).fn)

      assertGuardReleased(key)
    }

    it("after write throwing the key is released") {
      val key = keyFor("release-throw")
      val h = LauncherHarness()

      h.launch(key, "b") { _, _ -> throwRuntime("boom") }

      assertGuardReleased(key)
    }
  }

  describe("terminal branches") {
    it("Success reloads exactly once, with no prompt and no notify") {
      val key = keyFor("terminal-success")
      val h = LauncherHarness()

      h.launch(key, "typed text", WriteSpy(DiscussionWriteOutcome.Success).fn)

      h.reloadCount shouldBe 1
      h.notifications.shouldBeEmpty()
      h.retryPrompts.shouldBeEmpty()
      h.sendAgainPrompts.shouldBeEmpty()
      h.copyTextPrompts.shouldBeEmpty()
    }

    it("Success with an empty body (resolve/delete) behaves identically: one reload, nothing else") {
      val key = keyFor("terminal-success-empty")
      val h = LauncherHarness()

      h.launch(key, "", WriteSpy(DiscussionWriteOutcome.Success).fn)

      h.reloadCount shouldBe 1
      h.notifications.shouldBeEmpty()
      h.retryPrompts.shouldBeEmpty()
      h.sendAgainPrompts.shouldBeEmpty()
      h.copyTextPrompts.shouldBeEmpty()
    }

    it("Definite with a non-empty body prompts [Retry] once with exactly that body; notify is never called") {
      val key = keyFor("terminal-definite-body")
      val h = LauncherHarness()

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException())).fn)

      h.retryPrompts shouldContainExactly listOf(DiscussionWriteLauncher.DEFINITE_MESSAGE to "my comment")
      h.notifications.shouldBeEmpty()
      h.reloadCount shouldBe 0
      h.sendAgainPrompts.shouldBeEmpty()
      h.copyTextPrompts.shouldBeEmpty()
    }

    it("Definite with an empty body notifies once; [Retry] is never offered (nothing to re-send)") {
      val key = keyFor("terminal-definite-empty")
      val h = LauncherHarness()

      h.launch(key, "", WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException())).fn)

      h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.DEFINITE_MESSAGE)
      h.retryPrompts.shouldBeEmpty()
      h.reloadCount shouldBe 0
    }

    it("Ambiguous never offers [Retry] and always reloads first") {
      val key = keyFor("terminal-ambiguous-no-retry")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.retryPrompts.shouldBeEmpty()
      h.reloadCount shouldBe 1
    }

    it("Ambiguous whose reload yields Applied prompts [Send again] once with the preserved body") {
      val key = keyFor("ambiguous-applied")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.sendAgainPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.AMBIGUOUS_APPLIED_MESSAGE to "my comment")
      h.copyTextPrompts.shouldBeEmpty()
      h.notifications.shouldBeEmpty()
    }

    it("Ambiguous whose reload yields Superseded prompts copy-text once and never [Send again]") {
      val key = keyFor("ambiguous-superseded")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Superseded

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.copyTextPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.AMBIGUOUS_UNCONFIRMED_MESSAGE to "my comment")
      h.sendAgainPrompts.shouldBeEmpty()
    }

    it("Ambiguous whose reload yields Failed prompts copy-text once and never [Send again]") {
      val key = keyFor("ambiguous-failed")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Failed(RuntimeException())

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.copyTextPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.AMBIGUOUS_UNCONFIRMED_MESSAGE to "my comment")
      h.sendAgainPrompts.shouldBeEmpty()
    }

    it("Ambiguous whose reload yields GateRejected prompts copy-text once and never [Send again]") {
      val key = keyFor("ambiguous-gate-rejected")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.GateRejected

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.copyTextPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.AMBIGUOUS_UNCONFIRMED_MESSAGE to "my comment")
      h.sendAgainPrompts.shouldBeEmpty()
    }

    it("Ambiguous whose reload yields Skipped prompts copy-text once and never [Send again]") {
      val key = keyFor("ambiguous-skipped")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Skipped

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.copyTextPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.AMBIGUOUS_UNCONFIRMED_MESSAGE to "my comment")
      h.sendAgainPrompts.shouldBeEmpty()
    }

    it("Ambiguous with an empty body whose reload yields Applied notifies once, with no prompt") {
      val key = keyFor("ambiguous-empty-applied")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied

      h.launch(key, "", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.AMBIGUOUS_APPLIED_MESSAGE)
      // Asserted explicitly rather than left implicit in the message choice: resolve and delete have
      // no text to preserve, but they must still be shown the refreshed thread before being told the
      // result is unconfirmed.
      h.reloadCount shouldBe 1
      h.sendAgainPrompts.shouldBeEmpty()
      h.copyTextPrompts.shouldBeEmpty()
      h.retryPrompts.shouldBeEmpty()
    }

    it("Ambiguous with an empty body whose reload yields Superseded notifies the unconfirmed message, no prompt") {
      val key = keyFor("ambiguous-empty-superseded")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Superseded

      h.launch(key, "", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.AMBIGUOUS_UNCONFIRMED_MESSAGE)
      h.reloadCount shouldBe 1
      h.sendAgainPrompts.shouldBeEmpty()
      h.copyTextPrompts.shouldBeEmpty()
    }

    it("Ambiguous whose reload callback is never invoked shows nothing, throws nothing, and releases the key") {
      val key = keyFor("ambiguous-callback-dropped")
      val h = LauncherHarness()
      h.reloadOutcome = null // the loader's contract: callback deliberately dropped during shutdown

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)

      h.reloadCount shouldBe 1
      h.notifications.shouldBeEmpty()
      h.retryPrompts.shouldBeEmpty()
      h.sendAgainPrompts.shouldBeEmpty()
      h.copyTextPrompts.shouldBeEmpty()
      assertGuardReleased(key)
    }

    it("GateRejected with a non-empty body notifies once AND shows the text-preserving dialog once") {
      val key = keyFor("gate-rejected-body")
      val h = LauncherHarness()

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.GateRejected).fn)

      h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.CONNECTION_CHANGED_MESSAGE)
      h.copyTextPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.CONNECTION_CHANGED_MESSAGE to "my comment")
      h.reloadCount shouldBe 0
      h.retryPrompts.shouldBeEmpty()
      h.sendAgainPrompts.shouldBeEmpty()
    }

    it("GateRejected with an empty body notifies once and shows no dialog (nothing to preserve)") {
      val key = keyFor("gate-rejected-empty")
      val h = LauncherHarness()

      h.launch(key, "", WriteSpy(DiscussionWriteOutcome.GateRejected).fn)

      h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.CONNECTION_CHANGED_MESSAGE)
      h.copyTextPrompts.shouldBeEmpty()
    }

    it("Aborted produces no UI at all: zero notify, reload, and prompts of every kind") {
      val key = keyFor("aborted")
      val h = LauncherHarness()

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Aborted).fn)

      h.assertNoUiEffects()
    }
  }

  describe("guard scope at the terminal (lifecycle applied, freshness NOT applied)") {
    it("registryActive false at terminal time discards every UI effect but still releases the key") {
      val key = keyFor("lifecycle-inactive")
      val h = LauncherHarness(deferUi = true)
      h.reloadOutcome = LoadOutcome.Applied

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException())).fn)
      DiscussionGenerationRegistry.onDeactivate()
      h.runPendingUi()

      h.assertNoUiEffects()
      assertGuardReleased(key)
    }

    it("registryEpoch != startEpoch at terminal time (stop then start) discards every UI effect but releases the key") {
      val key = keyFor("lifecycle-epoch")
      val h = LauncherHarness(deferUi = true)

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Success).fn)
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.onActivate()
      h.runPendingUi()

      h.assertNoUiEffects()
      assertGuardReleased(key)
    }

    it("freshness is NOT a guard here: a bumped generation does not stop Success from reloading") {
      val key = keyFor("freshness-success")
      val h = LauncherHarness(deferUi = true)
      val readKey = DiscussionKey.of(INSTANCE_URL, AUTH_FINGERPRINT, 7L, 42L, 1L)

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Success).fn)
      DiscussionGenerationRegistry.nextGeneration(readKey)
      h.runPendingUi()

      h.reloadCount shouldBe 1
      assertGuardReleased(key)
    }

    it("freshness is NOT a guard here: a bumped generation does not stop Definite from prompting with the body") {
      val key = keyFor("freshness-definite")
      val h = LauncherHarness(deferUi = true)
      val readKey = DiscussionKey.of(INSTANCE_URL, AUTH_FINGERPRINT, 7L, 42L, 2L)

      h.launch(key, "my comment", WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException())).fn)
      DiscussionGenerationRegistry.nextGeneration(readKey)
      h.runPendingUi()

      h.retryPrompts shouldContainExactly listOf(DiscussionWriteLauncher.DEFINITE_MESSAGE to "my comment")
      assertGuardReleased(key)
    }
  }

  describe("re-entry from [Retry] and [Send again]") {
    it("the [Retry] callback re-sends with the edited body: write is called a second time with the new text") {
      val key = keyFor("retry-edited-body")
      val h = LauncherHarness()
      val write = WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException()), DiscussionWriteOutcome.Success)

      h.launch(key, "first draft", write.fn)
      h.lastRetryCallback!!.invoke("edited draft")

      write.bodies shouldContainExactly listOf("first draft", "edited draft")
    }

    it("the [Retry] re-entry re-acquires the guard: a key held at retry time rejects the re-send") {
      val key = keyFor("retry-reacquires")
      val h = LauncherHarness()
      val write = WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException()), DiscussionWriteOutcome.Success)

      h.launch(key, "first draft", write.fn)
      InFlightWriteGuard.tryAcquire(key) shouldBe true // simulate another in-flight write
      try {
        h.lastRetryCallback!!.invoke("edited draft")

        write.bodies shouldContainExactly listOf("first draft") // second send never happened
        h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.ALREADY_IN_PROGRESS_MESSAGE)
      } finally {
        InFlightWriteGuard.release(key)
      }
    }

    it("the [Retry] re-entry re-freezes startEpoch: after an epoch bump the retry's terminal UI still runs") {
      val key = keyFor("retry-refreezes-epoch")
      val h = LauncherHarness()
      val write = WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException()), DiscussionWriteOutcome.Success)

      h.launch(key, "first draft", write.fn)
      // Stop→restart between the first terminal and the user's click on [Retry].
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.onActivate()
      h.lastRetryCallback!!.invoke("first draft")

      // Reusing the stale original startEpoch would discard this terminal; a re-frozen epoch lets
      // the Success reload run.
      h.reloadCount shouldBe 1
      assertGuardReleased(key)
    }

    it("the [Send again] callback behaves the same: edited body, guard re-acquired, epoch re-frozen") {
      val key = keyFor("send-again-reentry")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied
      val write = WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException()), DiscussionWriteOutcome.Success)

      h.launch(key, "first draft", write.fn)
      h.sendAgainPrompts shouldContainExactly
        listOf(DiscussionWriteLauncher.AMBIGUOUS_APPLIED_MESSAGE to "first draft")
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.onActivate()
      h.lastSendAgainCallback!!.invoke("edited draft")

      write.bodies shouldContainExactly listOf("first draft", "edited draft")
      // reload #1 was the Ambiguous refresh; reload #2 is the re-sent Success terminal, which
      // only runs because the re-entry re-froze startEpoch from the current registry epoch.
      h.reloadCount shouldBe 2
      assertGuardReleased(key)
    }

    it("the first attempt's write receives exactly the startEpoch passed to launch") {
      val key = keyFor("epoch-passed-through")
      val h = LauncherHarness()
      val write = WriteSpy(DiscussionWriteOutcome.Success)

      // An arbitrary epoch, deliberately not the registry's: this asserts pass-through, not that
      // the launcher re-reads the registry. (The terminal is discarded by the lifecycle guard as a
      // result, which is exactly the defect being guarded against downstream.)
      h.launcher.launch(key, "first draft", 4242L, write.fn)

      write.epochs shouldContainExactly listOf(4242L)
      assertGuardReleased(key)
    }

    it("the [Retry] re-entry sends with the epoch frozen at retry time, not the original one") {
      val key = keyFor("retry-sends-new-epoch")
      val h = LauncherHarness()
      val write = WriteSpy(DiscussionWriteOutcome.Definite(RuntimeException()), DiscussionWriteOutcome.Success)
      val originalEpoch = DiscussionGenerationRegistry.currentEpoch

      h.launch(key, "first draft", write.fn)
      // Stop→restart while the [Retry] dialog is open.
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.onActivate()
      val newEpoch = DiscussionGenerationRegistry.currentEpoch
      h.lastRetryCallback!!.invoke("edited draft")

      newEpoch shouldNotBe originalEpoch
      // The argument value, not just the call count: sending the stale epoch would make the
      // pre-send lifecycle check abort — and Aborted shows no UI, so the confirmed text would
      // vanish silently.
      write.epochs shouldContainExactly listOf(originalEpoch, newEpoch)
      assertGuardReleased(key)
    }

    it("the [Send again] re-entry sends with the epoch frozen at click time, not the original one") {
      val key = keyFor("send-again-sends-new-epoch")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied
      val write = WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException()), DiscussionWriteOutcome.Success)
      val originalEpoch = DiscussionGenerationRegistry.currentEpoch

      h.launch(key, "first draft", write.fn)
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.onActivate()
      val newEpoch = DiscussionGenerationRegistry.currentEpoch
      h.lastSendAgainCallback!!.invoke("edited draft")

      newEpoch shouldNotBe originalEpoch
      write.epochs shouldContainExactly listOf(originalEpoch, newEpoch)
      assertGuardReleased(key)
    }

    it("the [Send again] re-entry re-acquires the guard: a key held at click time rejects the re-send") {
      val key = keyFor("send-again-reacquires")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied
      val write = WriteSpy(DiscussionWriteOutcome.Ambiguous(RuntimeException()), DiscussionWriteOutcome.Success)

      h.launch(key, "first draft", write.fn)
      InFlightWriteGuard.tryAcquire(key) shouldBe true
      try {
        h.lastSendAgainCallback!!.invoke("edited draft")

        write.bodies shouldContainExactly listOf("first draft")
        h.notifications shouldContainExactly listOf(DiscussionWriteLauncher.ALREADY_IN_PROGRESS_MESSAGE)
      } finally {
        InFlightWriteGuard.release(key)
      }
    }
  }

  describe("cancellation and safety") {
    it("CancellationException propagates out of the background block, is never an outcome, and releases the key") {
      val key = keyFor("cancellation")
      val h = LauncherHarness()

      shouldThrow<CancellationException> {
        h.launch(key, "my comment") { _, _ -> throw CancellationException("cancelled") }
      }

      // In particular it was NOT classified Ambiguous: no reload, no prompt, no notify.
      h.assertNoUiEffects()
      assertGuardReleased(key)
    }

    it("a write that throws takes the Definite path, releases the key, and logs only the type") {
      val key = keyFor("escaped-throwable")
      val h = LauncherHarness()
      h.reloadOutcome = LoadOutcome.Applied // would drive [Send again] if the outcome were wrongly Ambiguous

      h.launch(key, "my typed text") { _, _ -> throwRuntime("SECRET-MARKER boom") }

      // Definite flow, not the Ambiguous reload-then-[Send again] flow:
      h.retryPrompts shouldContainExactly listOf(DiscussionWriteLauncher.DEFINITE_MESSAGE to "my typed text")
      h.reloadCount shouldBe 0
      h.sendAgainPrompts.shouldBeEmpty()
      assertGuardReleased(key)
      h.logs.size shouldBe 1
      h.logs.single() shouldContain "exceptionType=RuntimeException"
      h.logs.single() shouldNotContain "SECRET-MARKER"
      h.logs.single() shouldNotContain "my typed text"
    }

    it("no log line ever contains the body") {
      val key = keyFor("body-never-logged")
      val h = LauncherHarness()

      h.launch(key, "SECRET-MARKER-BODY") { _, _ -> throwRuntime("boom") }

      // Sanity: the logging path really ran — the cleanliness assertion is not vacuous.
      h.logs.size shouldBe 1
      h.logs.forEach { it shouldNotContain "SECRET-MARKER-BODY" }
    }
  }

  describe("UI scheduling failure containment") {
    // The shared Koin CoroutineScope is CoroutineScope(Dispatchers.IO) — a plain Job, not a
    // SupervisorJob (utils/WorkspaceModule.kt:22). A throwable escaping the background block would
    // therefore cancel that scope for the whole session, taking the sidebar fetches, CI commands
    // and job-log loading down with it. Losing one completion dialog is the lesser harm.
    it("a runOnUi that throws does not propagate out of launch: no UI effect, and the key is released") {
      val key = keyFor("ui-scheduling-failure")
      val h = LauncherHarness()
      h.uiSchedulingFailure = IllegalStateException("Display is disposed")
      h.reloadOutcome = LoadOutcome.Applied

      h.launch(key, "my typed text", WriteSpy(DiscussionWriteOutcome.Success).fn)

      h.assertNoUiEffects()
      assertGuardReleased(key)
    }

    it("a runOnUi that throws logs the scheduling failure with the type only, and never the body") {
      val key = keyFor("ui-scheduling-failure-log")
      val h = LauncherHarness()
      h.uiSchedulingFailure = IllegalStateException("Display is disposed SECRET-MARKER")

      h.launch(key, "SECRET-MARKER-BODY", WriteSpy(DiscussionWriteOutcome.Success).fn)

      h.logs.size shouldBe 1
      h.logs.single() shouldContain "outcome=uiSchedulingFailed"
      h.logs.single() shouldContain "exceptionType=IllegalStateException"
      h.logs.single() shouldNotContain "SECRET-MARKER"
      // The write itself succeeded, so the escaped-throwable marker must NOT appear: the body
      // never ran, only the scheduling call failed.
      h.logs.single() shouldNotContain "outcome=escapedThrowable"
    }

    it("a runOnUi that throws CancellationException DOES propagate: structured concurrency needs it") {
      val key = keyFor("ui-scheduling-cancellation")
      val h = LauncherHarness()
      h.uiSchedulingFailure = CancellationException("display hop cancelled")

      shouldThrow<CancellationException> {
        h.launch(key, "my typed text", WriteSpy(DiscussionWriteOutcome.Success).fn)
      }

      h.assertNoUiEffects()
      assertGuardReleased(key)
      // Cancellation is rethrown, never logged as a scheduling failure.
      h.logs.shouldBeEmpty()
    }
  }
})
