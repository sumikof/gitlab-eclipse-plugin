package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A fake UI thread: [runOnUi] only queues; the test runs the queued actions by hand. */
internal class FakeUi {
  val queue = LinkedBlockingQueue<() -> Unit>()
  val runOnUi: (() -> Unit) -> Unit = { queue.add(it) }

  /** Waits (bounded) for the next posted action and runs it; false when none arrived. */
  fun runNext(timeoutMillis: Long = 5_000): Boolean {
    val action = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return false
    action()
    return true
  }

  /** Waits (bounded) until at least [n] actions are queued, without running them. */
  fun awaitQueued(n: Int, timeoutMillis: Long = 5_000): Boolean {
    val until = System.currentTimeMillis() + timeoutMillis
    while (queue.size < n) {
      if (System.currentTimeMillis() > until) return false
      Thread.sleep(5)
    }
    return true
  }

  fun drain() {
    while (true) {
      val action = queue.poll() ?: return
      action()
    }
  }
}

/** A fake UI timer: nothing fires until the test calls [fireAll]; a cancelled entry is dropped. */
internal class FakeTimer {
  inner class Entry(val delayMillis: Long, val action: () -> Unit) : Cancellable {
    var cancelled = false

    override fun cancel() {
      cancelled = true
      entries.remove(this)
    }
  }

  val entries = mutableListOf<Entry>()
  val schedule: (Long, () -> Unit) -> Cancellable = { delay, action -> Entry(delay, action).also { entries += it } }

  fun fireAll() {
    entries.toList().forEach {
      entries.remove(it)
      it.action()
    }
  }
}

/** Records what the session shows; can be told to throw. */
internal class RecordingView : QuickChatView {
  val rendered = mutableListOf<InlineThreadModel>()
  val released = mutableListOf<Pair<SubmitTicket, Boolean>>()
  var failRender = false
  var failReleased = false

  override fun render(model: InlineThreadModel) {
    rendered += model
    if (failRender) error("render failed")
  }

  override fun released(ticket: SubmitTicket, succeeded: Boolean) {
    released += ticket to succeeded
    if (failReleased) error("released failed")
  }

  fun bodies(): List<String> = rendered.last().items.single().entries.map { it.body }
}

/**
 * A call that blocks until [release], ignoring interrupts (a stuck OAuth refresh or JGit I/O, design
 * §15.3). [entered] counts down once the call is blocked.
 */
internal class StuckCall {
  val entered = CountDownLatch(1)
  private val release = CountDownLatch(1)

  fun block() {
    entered.countDown()
    var interrupted = false
    while (true) {
      try {
        release.await()
        break
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  fun awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)

  fun release() = release.countDown()
}

/** [QuickChatConnections] whose calls get stuck in [stuck] (ignoring interrupts) before answering. */
internal class StuckConnections(val stuck: StuckCall = StuckCall()) : QuickChatConnections {
  override fun capture() = stuck.block().let { snapshot() }

  override fun captureIf(instanceUrl: String) = stuck.block().let { snapshot() }
}

internal fun ticket(body: String = "What does this do?", generation: Long = 0) =
  SubmitTicket("quick-chat", body, generation)

internal fun okContext(question: String) =
  CapturedContext(ContextResult.Ok(QuickChatContext(question, null)), null)

/** Waits (bounded) until no detached job is counted, so no spec leaks a count into another. */
internal fun awaitNoDetachedJobs(timeoutMillis: Long = 10_000): Boolean {
  val until = System.currentTimeMillis() + timeoutMillis
  while (QuickChatDetachedJobs.count != 0) {
    if (System.currentTimeMillis() > until) return false
    Thread.sleep(10)
  }
  return true
}
