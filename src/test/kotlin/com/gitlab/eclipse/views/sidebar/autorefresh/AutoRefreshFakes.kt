package com.gitlab.eclipse.views.sidebar.autorefresh

/** A virtual clock and `timerExec`: nothing runs until [advanceBy] passes an action's due time. */
internal class VirtualTime : AutoRefreshClock, AutoRefreshTimer {
  private class Task(val dueAt: Long, val seq: Long, val action: () -> Unit) {
    var cancelled = false
  }

  private val byDueThenSeq = compareBy<Task>({ it.dueAt }, { it.seq })
  private var now = 1_000_000L
  private var seq = 0L
  private val tasks = mutableListOf<Task>()

  /** Timers scheduled and neither run nor cancelled. */
  val pendingCount: Int
    get() = tasks.count { !it.cancelled }

  /** Every delay ever passed to [schedule], in order. */
  val scheduledDelays = mutableListOf<Long>()

  /** When set, [Cancellable.cancel] is lost and the action still runs: proves a late timer is inert. */
  var loseCancels = false

  override fun nowMillis(): Long = now

  override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
    scheduledDelays += delayMillis
    val task = Task(now + delayMillis, seq++, action)
    tasks += task
    return Cancellable { if (!loseCancels) task.cancelled = true }
  }

  fun advanceBy(millis: Long) {
    val target = now + millis
    while (true) {
      val next = tasks.filter { !it.cancelled && it.dueAt <= target }.minWithOrNull(byDueThenSeq) ?: break
      tasks -= next
      now = next.dueAt
      next.action()
    }
    tasks.removeAll { it.cancelled }
    now = target
  }
}

/** A switchable [ActivitySource] that counts live registrations and can be told to throw on unregister. */
internal class FakeActivity(var active: Boolean = true) : ActivitySource {
  val listeners = mutableListOf<(Boolean) -> Unit>()
  var registered = 0
  var unregistered = 0
  var failUnregister: RuntimeException? = null

  override fun isActive(): Boolean = active

  override fun register(onChange: (Boolean) -> Unit): Registration {
    registered++
    listeners += onChange
    return Registration {
      unregistered++
      failUnregister?.let { throw it }
    }
  }

  /** Flips the state and notifies every listener ever registered (stale ones included, to prove they are inert). */
  fun set(value: Boolean) {
    active = value
    listeners.toList().forEach { it(value) }
  }
}

internal class FakeRefs : RefsEventSource {
  val listeners = mutableListOf<(String) -> Unit>()
  var registered = 0
  var unregistered = 0
  var failUnregister: RuntimeException? = null

  override fun register(onRefsChanged: (gitDir: String) -> Unit): Registration {
    registered++
    listeners += onRefsChanged
    return Registration {
      unregistered++
      failUnregister?.let { throw it }
    }
  }

  fun fire(gitDir: String) = listeners.toList().forEach { it(gitDir) }
}

/** Records probes; the test answers them with [answer]. */
internal class FakeProbe : HeadProbe {
  val requests = mutableListOf<Pair<String, (HeadKey) -> Unit>>()
  private var answered = 0

  val outstanding: Int
    get() = requests.size - answered

  override fun probe(gitDir: String, onResult: (HeadKey) -> Unit) {
    requests += gitDir to onResult
  }

  /** Answers the oldest unanswered probe. */
  fun answer(key: HeadKey) {
    val (_, onResult) = requests[answered++]
    onResult(key)
  }
}

internal class FakePort : SoftRefreshPort {
  val tickets = mutableListOf<Long>()
  var failStart: RuntimeException? = null

  override fun startSoftRefresh(ticket: Long) {
    tickets += ticket
    failStart?.let { throw it }
  }
}
