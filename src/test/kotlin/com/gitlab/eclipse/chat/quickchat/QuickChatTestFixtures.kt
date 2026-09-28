package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A [MonotonicClock] the test moves by hand. */
internal class FakeClock(start: Long = 0L) : MonotonicClock {
  private val now = AtomicLong(start)

  override fun nanoTime(): Long = now.get()

  fun advanceNanos(nanos: Long) {
    now.addAndGet(nanos)
  }

  fun advance(duration: kotlin.time.Duration) = advanceNanos(duration.inWholeNanoseconds)
}

internal const val INSTANCE = "https://gitlab.com"

internal fun snapshot(url: String = INSTANCE) = ConnectionSnapshot(url, "tok", "fp", 0L)

internal val PROJECT_KEY = ProjectKey.resolved(INSTANCE, "group/proj")
internal val PROJECT_PREFLIGHT = Preflight("gid://gitlab/Project/1", PROJECT_KEY)

/** A [QuickChatConnections] answering from mutable fields; counts every call. */
internal class FakeConnections(
  var current: ConnectionSnapshot = snapshot(),
) : QuickChatConnections {
  var captureCalls = 0
  val captureIfCalls = mutableListOf<String>()
  var failWith: Exception? = null

  override fun capture(): ConnectionSnapshot {
    captureCalls++
    failWith?.let { throw it }
    return current
  }

  override fun captureIf(instanceUrl: String): ConnectionSnapshot? {
    captureIfCalls += instanceUrl
    failWith?.let { throw it }
    return current.takeIf { it.instanceUrl.trimEnd('/') == instanceUrl.trimEnd('/') }
  }
}

/**
 * A scope on threads of its own, so a test can block a fake inside `runInterruptible` and cancel it.
 * [close] interrupts anything still running there.
 */
internal class BackgroundScope : AutoCloseable {
  private val pool = Executors.newCachedThreadPool()
  val scope = CoroutineScope(pool.asCoroutineDispatcher())

  override fun close() {
    pool.shutdownNow()
  }
}
