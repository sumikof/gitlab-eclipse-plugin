package com.gitlab.eclipse.lsp.messages

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerPhase
import com.gitlab.eclipse.lsp.lifecycle.StartOrigin
import com.gitlab.eclipse.utils.DebugLogging
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val OFF_UI_THREAD = "test-off-ui"
private const val WAIT_SECONDS = 5L

class LanguageServerMessagePresenterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var offUiPool: ExecutorService

  beforeEach { offUiPool = Executors.newSingleThreadExecutor { task -> Thread(task, OFF_UI_THREAD) } }

  afterEach { offUiPool.shutdownNow() }

  /** A dialog the presenter was handed, with what it saw when it was dismissed. */
  class FakeDialog {
    var dismissed = 0
    var futureDoneAtDismiss: Boolean? = null

    /** Whether every dismiss ran as its own UI turn: on the UI thread, dispatched after the dialog opened. */
    var dismissedInOwnUiTurn: Boolean? = null
  }

  class Fixture(offUiPool: ExecutorService) {
    val session = LanguageServerSession()
    var current: LanguageServerSession? = session
    var closed = false
    var workbench = true
    var now = 0L

    /** The UI thread's queue; the test thread plays the UI thread when it drains it. */
    val queued = mutableListOf<Runnable>()
    var dispatchFails = false
    var notifyFailure: RuntimeException? = null
    val notified = mutableListOf<Pair<MessageType, String>>()
    val asked = mutableListOf<Triple<MessageType, String, List<String>>>()
    val dialogs = mutableListOf<FakeDialog>()

    /** What the dialog does while it is "open"; receives the fake dialog. Returns the return code. */
    var dialogBehaviour: (FakeDialog) -> Int = { -1 }
    var future: CompletableFuture<MessageActionItem?>? = null

    val presenter = LanguageServerMessagePresenter(
      currentSession = { current },
      isClosed = { closed },
      uiDispatch = {
        if (dispatchFails) throw SWTException(SWT.ERROR_DEVICE_DISPOSED)
        synchronized(queued) { queued += it }
      },
      offUi = { offUiPool.execute(it) },
      dedupe = ShowMessageDeduplicator(nowMillis = { now }),
      notify = { type, text ->
        notifyFailure?.let { throw it }
        notified += type to text
      },
      ask = { type, text, buttons, onDialog ->
        asked += Triple(type, text, buttons)
        val dialog = FakeDialog()
        dialogs += dialog
        onDialog(
          DialogHandle {
            dialog.dismissed++
            dialog.futureDoneAtDismiss = future?.isDone
            // The dialog's own UI turn is depth 1; a dismiss dispatched to the UI runs nested in its
            // modal loop (depth 2). Inline on another thread, or inline in the dialog's turn, is wrong.
            val ownTurn = Thread.currentThread() === uiThread && uiDepth >= 2
            dialog.dismissedInOwnUiTurn = (dialog.dismissedInOwnUiTurn ?: true) && ownTurn
          },
        )
        dialogBehaviour(dialog)
      },
      workbenchAvailable = { workbench },
    )

    /** The thread draining the UI queue, and how deeply UI turns are nested on it (a modal loop nests). */
    @Volatile
    var uiThread: Thread? = null

    @Volatile
    var uiDepth = 0

    fun runUi() {
      uiThread = Thread.currentThread()
      while (true) {
        val next = synchronized(queued) { if (queued.isEmpty()) null else queued.removeAt(0) } ?: return
        uiDepth++
        try {
          next.run()
        } finally {
          uiDepth--
        }
      }
    }

    /** A modal loop: keeps running UI work until [done] holds (or the wait runs out). */
    fun pumpUntil(done: () -> Boolean) {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
      while (!done() && System.nanoTime() < deadline) {
        runUi()
        Thread.sleep(1)
      }
    }

    fun request(
      actions: List<MessageActionItem>?,
      type: MessageType? = MessageType.Warning,
      text: String = "pick one",
    ): CompletableFuture<MessageActionItem?> {
      val params = ShowMessageRequestParams().apply {
        actions?.let { this.actions = it }
        type?.let { this.type = it }
        message = text
      }
      return presenter.request(session, params).also { future = it }
    }
  }

  // lsp4j's setters reject null; a type-less message can still arrive over the wire (Gson).
  fun message(type: MessageType?, text: String = "hello") = MessageParams().apply {
    type?.let { this.type = it }
    message = text
  }

  fun <T> CompletableFuture<T>.await(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

  describe("show") {
    it("shows Error, Warning and Info with their type, on the UI turn") {
      val f = Fixture(offUiPool)

      listOf(MessageType.Error, MessageType.Warning, MessageType.Info).forEach {
        f.presenter.show(f.session, message(it, "text-$it"))
      }
      f.notified.shouldBeEmpty()
      f.runUi()

      f.notified shouldContainExactly listOf(
        MessageType.Error to "text-Error",
        MessageType.Warning to "text-Warning",
        MessageType.Info to "text-Info",
      )
    }

    it("drops Log and type-less messages without a UI turn") {
      val f = Fixture(offUiPool)

      f.presenter.show(f.session, message(MessageType.Log))
      f.presenter.show(f.session, message(null))

      f.queued.shouldBeEmpty()
      f.runUi()
      f.notified.shouldBeEmpty()
    }

    it("does not show a message of a session that is no longer current") {
      val f = Fixture(offUiPool)
      f.presenter.show(f.session, message(MessageType.Error))

      f.current = LanguageServerSession()
      f.runUi()

      f.notified.shouldBeEmpty()
    }

    it("does not show a message once there is no current session") {
      val f = Fixture(offUiPool)
      f.presenter.show(f.session, message(MessageType.Error))

      f.current = null
      f.runUi()

      f.notified.shouldBeEmpty()
    }

    it("does not show a message once the lifecycle store is closed") {
      val f = Fixture(offUiPool)
      f.presenter.show(f.session, message(MessageType.Error))

      f.closed = true
      f.runUi()

      f.notified.shouldBeEmpty()
    }

    it("does not show a message while the workbench is not available") {
      val f = Fixture(offUiPool)
      f.presenter.show(f.session, message(MessageType.Error))

      f.workbench = false
      f.runUi()

      f.notified.shouldBeEmpty()
    }

    it("shows an identical message of the same session once within 30 s, and again after") {
      val f = Fixture(offUiPool)

      f.presenter.show(f.session, message(MessageType.Error, "same"))
      f.runUi()
      f.now += 29_999
      f.presenter.show(f.session, message(MessageType.Error, "same"))
      f.runUi()
      f.notified.size shouldBe 1

      f.now += 1
      f.presenter.show(f.session, message(MessageType.Error, "same"))
      f.runUi()
      f.notified.size shouldBe 2
    }

    it("does not let a dropped stale message take a dedupe slot") {
      val f = Fixture(offUiPool)
      f.presenter.show(f.session, message(MessageType.Error, "same"))
      f.closed = true
      f.runUi()

      f.closed = false
      f.presenter.show(f.session, message(MessageType.Error, "same"))
      f.runUi()

      f.notified.size shouldBe 1
    }

    it("truncates the text to 1000 characters plus an ellipsis") {
      val f = Fixture(offUiPool)

      f.presenter.show(f.session, message(MessageType.Info, "a".repeat(1001)))
      f.presenter.show(f.session, message(MessageType.Info, "b".repeat(1000)))
      f.runUi()

      f.notified[0].second shouldBe "a".repeat(1000) + "…"
      f.notified[1].second shouldBe "b".repeat(1000)
    }

    it("treats a missing text as empty") {
      val f = Fixture(offUiPool)

      f.presenter.show(f.session, MessageParams().apply { type = MessageType.Info })
      f.runUi()

      f.notified shouldContainExactly listOf(MessageType.Info to "")
    }

    it("contains a failing popup in the UI turn and logs only the exception class") {
      val log = mockk<ILog>(relaxed = true)
      val statuses = mutableListOf<IStatus>()
      every { log.log(capture(statuses)) } returns Unit
      every { log.bundle.symbolicName } returns "test.bundle"
      every { Platform.getLog(any<Bundle>()) } returns log
      every { Platform.getLog(any<Class<*>>()) } returns log
      val f = Fixture(offUiPool)
      f.notifyFailure = IllegalStateException("SENTINEL-EXCEPTION")
      f.presenter.show(f.session, message(MessageType.Error, "SENTINEL-TEXT"))

      shouldNotThrowAny { f.runUi() }

      verify(exactly = 1) { log.warn("showMessage popup failed (IllegalStateException).") }
      verify(exactly = 0) { log.warn(match<String> { it.contains("SENTINEL") }) }
      verify(exactly = 0) { log.warn(any(), any()) }
      statuses.forEach { it.message shouldNotContain "SENTINEL" }
    }

    it("is a silent no-op when the UI hop fails") {
      val f = Fixture(offUiPool)
      f.dispatchFails = true

      shouldNotThrowAny { f.presenter.show(f.session, message(MessageType.Error)) }
    }
  }

  describe("request") {
    val first = MessageActionItem("First")
    val second = MessageActionItem("Second")

    it("returns the future before the UI turn runs") {
      val f = Fixture(offUiPool)

      val future = f.request(listOf(first, second))

      future.isDone shouldBe false
      f.asked.shouldBeEmpty()
      f.queued.size shouldBe 1
    }

    it("asks with the type, text and action titles in order") {
      val f = Fixture(offUiPool)

      f.request(listOf(first, second), MessageType.Error, "choose")
      f.runUi()

      f.asked shouldContainExactly listOf(Triple(MessageType.Error, "choose", listOf("First", "Second")))
    }

    it("answers the very action item at the chosen index") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { 1 }

      val future = f.request(listOf(first, second))
      f.runUi()

      future.await() shouldBeSameInstanceAs second
    }

    it("answers null when the dialog was closed without a choice") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { SWT.DEFAULT }

      val future = f.request(listOf(first, second))
      f.runUi()

      future.await().shouldBeNull()
    }

    it("answers null for a return code outside the actions") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { 2 }

      val future = f.request(listOf(first, second))
      f.runUi()

      future.await().shouldBeNull()
    }

    it("offers only OK when there are no actions, and answers null") {
      listOf(emptyList<MessageActionItem>(), null).forEach { actions ->
        val f = Fixture(offUiPool)
        f.dialogBehaviour = { 0 }

        val future = f.request(actions)
        f.runUi()

        f.asked.single().third shouldContainExactly listOf("OK")
        future.await().shouldBeNull()
      }
    }

    it("asks a Log or type-less request as Info") {
      val f = Fixture(offUiPool)

      f.request(listOf(first), MessageType.Log)
      f.request(listOf(first), null)
      f.runUi()

      f.asked.map { it.first } shouldContainExactly listOf(MessageType.Info, MessageType.Info)
    }

    it("truncates the request text too") {
      val f = Fixture(offUiPool)

      f.request(listOf(first), text = "c".repeat(1500))
      f.runUi()

      f.asked.single().second shouldBe "c".repeat(1000) + "…"
    }

    it("answers null when the dialog cannot open (display disposed)") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { throw SWTException(SWT.ERROR_WIDGET_DISPOSED) }

      val future = f.request(listOf(first))
      f.runUi()

      future.await().shouldBeNull()
    }

    it("answers null when the dialog fails in any other way") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { throw IllegalStateException("x") }

      val future = f.request(listOf(first))
      f.runUi()

      future.await().shouldBeNull()
    }

    it("answers null when the UI hop fails") {
      val f = Fixture(offUiPool)
      f.dispatchFails = true

      val future = f.request(listOf(first))

      future.await().shouldBeNull()
    }

    it("answers null without a dialog when the session is stale, the store closed or the workbench gone") {
      listOf<(Fixture) -> Unit>(
        { it.current = LanguageServerSession() },
        { it.closed = true },
        { it.workbench = false },
      ).forEach { change ->
        val f = Fixture(offUiPool)
        val future = f.request(listOf(first))

        change(f)
        f.runUi()

        f.asked.shouldBeEmpty()
        future.await().shouldBeNull()
      }
    }

    it("completes on the off-UI executor, never on the UI thread") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { 0 }
      val future = f.request(listOf(first))
      val completedOn = CompletableFuture<String>()
      future.whenComplete { _, _ -> completedOn.complete(Thread.currentThread().name) }

      f.runUi()

      // Wait on the probe, not on the request: a thread blocked in get() may run its dependents itself.
      completedOn.await() shouldBe OFF_UI_THREAD
      future.await() shouldBeSameInstanceAs first
    }

    it("completes on the off-UI executor when the session ends") {
      val f = Fixture(offUiPool)
      val future = f.request(listOf(first))
      val completedOn = CompletableFuture<String>()
      future.whenComplete { _, _ -> completedOn.complete(Thread.currentThread().name) }

      f.presenter.sessionEnded(f.session)

      completedOn.await() shouldBe OFF_UI_THREAD
      future.await().shouldBeNull()
    }

    it("answers null when the session ends while the dialog is open, then dismisses the dialog") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { dialog ->
        f.presenter.sessionEnded(f.session)
        f.pumpUntil { dialog.dismissed > 0 }
        // A programmatically closed JFace dialog would otherwise report OK = 0 = actions[0].
        0
      }

      val future = f.request(listOf(first, second))
      f.runUi()

      future.await().shouldBeNull()
      val dialog = f.dialogs.single()
      dialog.dismissed shouldBe 1
      dialog.dismissedInOwnUiTurn shouldBe true
      dialog.futureDoneAtDismiss shouldBe true
    }

    it("does not open the dialog when the session ended before the UI turn") {
      val f = Fixture(offUiPool)
      val future = f.request(listOf(first))

      f.presenter.sessionEnded(f.session)
      future.await().shouldBeNull()
      f.runUi()

      f.asked.shouldBeEmpty()
    }

    it("leaves requests of other sessions pending when a session ends") {
      val f = Fixture(offUiPool)
      val future = f.request(listOf(first))

      f.presenter.sessionEnded(LanguageServerSession())
      offUiPool.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)

      future.isDone shouldBe false
    }

    it("dismisses the dialog when the server cancels the request") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { dialog ->
        f.future!!.cancel(true)
        f.pumpUntil { dialog.dismissed > 0 }
        0
      }

      val future = f.request(listOf(first))
      f.runUi()

      future.isCancelled shouldBe true
      f.dialogs.single().dismissed shouldBe 1
      f.dialogs.single().dismissedInOwnUiTurn shouldBe true
    }

    it("ignores a click that comes after the request was answered with null") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = {
        f.presenter.sessionEnded(f.session)
        f.future!!.await()
        1
      }

      val future = f.request(listOf(first, second))
      f.runUi()
      offUiPool.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)

      future.await().shouldBeNull()
    }

    it("does not dismiss a dialog that already returned") {
      val f = Fixture(offUiPool)
      f.dialogBehaviour = { 1 }

      val future = f.request(listOf(first, second))
      f.runUi()
      future.await()
      f.runUi()

      f.dialogs.single().dismissed shouldBe 0
    }
  }

  describe("lifecycle transitions end sessions (T6)") {
    val first = MessageActionItem("First")

    fun state(phase: LanguageServerPhase, generation: Long) =
      LanguageServerLifecycleState(phase, generation, StartOrigin.STARTUP, null)

    fun drainOffUi() = offUiPool.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)

    listOf(
      "STOPPED" to state(LanguageServerPhase.STOPPED, 1),
      "FAILED" to state(LanguageServerPhase.FAILED, 1),
      "a new attempt (STARTING)" to state(LanguageServerPhase.STARTING, 2),
    ).forEach { (name, new) ->
      it("answers every pending request with null, off the calling thread, on $name") {
        val f = Fixture(offUiPool)
        val future = f.request(listOf(first))
        val completedOn = CompletableFuture<String>()
        future.whenComplete { _, _ -> completedOn.complete(Thread.currentThread().name) }

        // Even while the wrapper still publishes the failing session (FAILED precedes the teardown).
        f.presenter.onTransition(state(LanguageServerPhase.RUNNING, 1), new)

        completedOn.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe OFF_UI_THREAD
        future.await().shouldBeNull()
      }
    }

    it("on RUNNING keeps the current session's request and ends the others") {
      val f = Fixture(offUiPool)
      val current = f.request(listOf(first))
      val stale = LanguageServerSession()
      val staleFuture = f.presenter.request(stale, ShowMessageRequestParams().apply { message = "x" })

      f.presenter.onTransition(state(LanguageServerPhase.STARTING, 2), state(LanguageServerPhase.RUNNING, 2))
      staleFuture.await().shouldBeNull()
      drainOffUi()

      current.isDone shouldBe false
    }

    it("sessionsEndedExcept(null) ends every session (bundle stop)") {
      val f = Fixture(offUiPool)
      val future = f.request(listOf(first))

      f.presenter.sessionsEndedExcept(null)

      future.await().shouldBeNull()
    }
  }

  describe("logging") {
    it("never logs the message text or action titles") {
      val log = mockk<ILog>(relaxed = true)
      val statuses = mutableListOf<IStatus>()
      every { log.log(capture(statuses)) } returns Unit
      every { log.bundle.symbolicName } returns "test.bundle"
      every { Platform.getLog(any<Bundle>()) } returns log
      every { Platform.getLog(any<Class<*>>()) } returns log
      val previous = DebugLogging.isEnabled
      DebugLogging.isEnabled = { true }
      try {
        val f = Fixture(offUiPool)
        f.dialogBehaviour = { throw IllegalStateException("SENTINEL-EXCEPTION") }
        f.presenter.show(f.session, message(MessageType.Error, "SENTINEL-TEXT"))
        f.presenter.show(f.session, message(MessageType.Log, "SENTINEL-TEXT"))
        f.request(listOf(MessageActionItem("SENTINEL-TITLE")), text = "SENTINEL-TEXT")
        f.runUi()
        f.future!!.await()
      } finally {
        DebugLogging.isEnabled = previous
      }

      val messages = statuses.map { it.message }
      messages.joinToString("\n") shouldContain "showMessage received (type=ERROR)"
      messages.forEach {
        it shouldNotContain "SENTINEL"
      }
      statuses.forEach { it.exception.shouldBeNull() }
      verify(exactly = 0) { log.info(match<String> { it.contains("SENTINEL") }) }
      verify(exactly = 0) { log.warn(match<String> { it.contains("SENTINEL") }) }
      verify(exactly = 0) { log.error(match<String> { it.contains("SENTINEL") }) }
      verify(exactly = 0) { log.warn(any(), any()) }
      verify(exactly = 0) { log.error(any(), any()) }
    }
  }
})
