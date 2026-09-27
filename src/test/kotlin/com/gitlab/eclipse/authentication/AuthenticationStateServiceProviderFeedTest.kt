package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Spec for the `AuthenticationStateService` -> `AuthenticationSourceProvider` feed (design §9.1 /
 * §23) including the deterministic races.
 *
 * Every real-machine dependency goes through a seam: the current-connection read, the debounce
 * delay, the UI dispatch and the popup display. Nothing here touches `Display`, so the spec runs
 * headless. The races are made deterministic by pausing a specific thread inside the connection
 * read (the one call both the service and the provider make while deciding) and by releasing the
 * debounce of each job explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationStateServiceProviderFeedTest : DescribeSpec({

  val key = AuthenticationSourceProvider.SIGN_IN_REQUIRED_KEY
  val waitSeconds = 10L

  fun change(vararg checks: Pair<String, Boolean>) = FeatureStateChange(
    featureId = "authentication",
    allChecks = checks.map { (id, engaged) -> FeatureStateChangeCheck(checkId = id, engaged = engaged) }
  )

  val unauthenticated = change("authentication-required" to true)
  val authenticated = change("authentication-required" to false)
  val invalidTokenOnly = change("authentication-required" to false, "invalid-token" to true)
  val unrelatedOnly = change("other-check" to true)
  val noChecks = FeatureStateChange(featureId = "authentication", allChecks = null)

  /** A pause inserted into the n-th connection read made by one thread. */
  class Pause(val thread: Thread, val nth: Int) {
    val count = AtomicInteger(0)
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)

    fun awaitEntered() = check(entered.await(10, TimeUnit.SECONDS)) { "the paused thread never reached the read" }
  }

  /** A fake of `GitLabLanguageServerWrapper.currentSnapshot?.session` whose reads can be paused per thread. */
  class FakeConnection {
    @Volatile
    var session: LanguageServerSession? = LanguageServerSession()

    @Volatile
    var pause: Pause? = null

    /** Returns the value read *before* the pause, so the reader continues with a stale view. */
    val read: () -> LanguageServerSession? = {
      val stale = session
      val p = pause
      if (p != null && Thread.currentThread() === p.thread && p.count.incrementAndGet() == p.nth) {
        p.entered.countDown()
        check(p.resume.await(10, TimeUnit.SECONDS)) { "the paused thread was never resumed" }
      }
      stale
    }
  }

  /** Every debounce suspends on its own gate until the test releases it; cancellation is honoured. */
  class GatedDebounce {
    val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    val seam: suspend (Long) -> Unit = {
      val gate = CompletableDeferred<Unit>()
      gates += gate
      gate.await()
    }

    fun release(index: Int) = gates[index].complete(Unit)
  }

  class Harness(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined), notifDelay: Long = 1000L) {
    val connection = FakeConnection()
    val debounce = GatedDebounce()
    val uiQueue = CopyOnWriteArrayList<Runnable>()
    val popups = AtomicInteger(0)
    val provider = spyk(AuthenticationSourceProvider(connection.read, { it.run() }))
    val service = AuthenticationStateService(
      scope = scope,
      notifDelay = notifDelay,
      sourceProvider = { provider },
      currentSession = connection.read,
      uiDispatch = { uiQueue += it },
      debounce = debounce.seam,
      showPopup = { popups.incrementAndGet() },
    )

    val session: LanguageServerSession get() = connection.session!!
    val published: Any? get() = provider.currentState[key]

    /** Runs everything the service handed to the UI thread, in order. */
    fun drainUi() {
      while (uiQueue.isNotEmpty()) uiQueue.removeAt(0).run()
    }
  }

  fun awaitBlockedOnLock(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
    while (thread.state != Thread.State.BLOCKED) {
      check(System.nanoTime() < deadline) { "thread never blocked on the lock (state ${thread.state})" }
      Thread.sleep(1)
    }
  }

  describe("provider feed") {
    it("with the real debounce, only the latest of stale -> latest reaches the provider, once") {
      val testScope = TestScope(StandardTestDispatcher())
      val connection = FakeConnection()
      val provider = spyk(AuthenticationSourceProvider(connection.read, { it.run() }))
      val service = AuthenticationStateService(
        scope = testScope,
        notifDelay = 100L,
        sourceProvider = { provider },
        currentSession = connection.read,
        uiDispatch = { it.run() },
        showPopup = {},
      )

      service.update(unauthenticated, connection.session!!)
      testScope.advanceTimeBy(50L)
      service.update(authenticated, connection.session!!)
      testScope.advanceTimeBy(101L)

      verify(exactly = 1) { provider.update(any(), any(), any()) }
      verify(exactly = 1) { provider.update(authenticated, connection.session!!, any()) }
      provider.currentState[key] shouldBe false
    }

    it("a late notification from the old session neither cancels the new session's debounce nor is applied") {
      val h = Harness()
      val old = h.session
      val next = LanguageServerSession()
      h.connection.session = next

      h.service.update(unauthenticated, next)
      h.service.update(authenticated, old)
      h.debounce.gates.size shouldBe 1
      h.debounce.release(0)

      verify(exactly = 1) { h.provider.update(unauthenticated, next, any()) }
      verify(exactly = 0) { h.provider.update(authenticated, any(), any()) }
      h.published shouldBe true
    }

    it("the same auth value before and after an LS restart still updates the provider for the new session") {
      val h = Harness()
      val old = h.session

      h.service.update(unauthenticated, old)
      h.debounce.release(0)
      h.published shouldBe true

      h.connection.session = null
      h.service.resetForConnectionChange()
      h.published shouldBe false

      val next = LanguageServerSession()
      h.connection.session = next
      h.service.update(unauthenticated, next)
      h.debounce.release(1)

      verify(exactly = 1) { h.provider.update(unauthenticated, next, any()) }
      h.provider.stored?.session shouldBe next
      h.published shouldBe true
    }

    it("the provider is notified before the unchanged-state early return") {
      val h = Harness()

      h.service.update(unauthenticated, h.session)
      h.debounce.release(0)
      h.drainUi()
      h.popups.get() shouldBe 1

      h.service.update(unauthenticated, h.session)
      h.debounce.release(1)

      verify(exactly = 2) { h.provider.update(unauthenticated, h.session, any()) }
      h.drainUi()
      h.popups.get() shouldBe 1
    }

    it("the provider receives the debounced change whatever allChecks contains") {
      val h = Harness()

      h.service.update(noChecks, h.session)
      h.debounce.release(0)
      h.service.update(invalidTokenOnly, h.session)
      h.debounce.release(1)
      h.service.update(unrelatedOnly, h.session)
      h.debounce.release(2)

      verifyOrder {
        h.provider.update(noChecks, h.session, any())
        h.provider.update(invalidTokenOnly, h.session, any())
        h.provider.update(unrelatedOnly, h.session, any())
      }
      verify(exactly = 3) { h.provider.update(any(), any(), any()) }
      h.drainUi()
      h.popups.get() shouldBe 0
    }

    it("authenticated -> resetAuthenticatedState() -> unauthenticated notification publishes true") {
      val h = Harness()

      h.service.update(authenticated, h.session)
      h.debounce.release(0)
      h.published shouldBe false

      h.service.resetAuthenticatedState()
      h.provider.authState shouldBe AuthState.UNKNOWN
      h.published shouldBe false

      h.service.update(unauthenticated, h.session)
      h.debounce.release(1)

      h.published shouldBe true
      h.drainUi()
      h.popups.get() shouldBe 1
    }

    it("unauthenticated -> resetAuthenticatedState() publishes false at once and voids the in-flight debounce") {
      val h = Harness()

      h.service.update(unauthenticated, h.session)
      h.debounce.release(0)
      h.published shouldBe true

      h.service.update(unauthenticated, h.session)
      h.service.resetAuthenticatedState()
      h.published shouldBe false
      h.provider.authState shouldBe AuthState.UNKNOWN

      h.debounce.release(1)
      verify(exactly = 1) { h.provider.update(any(), any(), any()) }
      h.published shouldBe false

      h.service.update(authenticated, h.session)
      h.debounce.release(2)
      h.provider.authState shouldBe AuthState.AUTHENTICATED
      h.published shouldBe false
    }

    it("keeps the existing popup rules") {
      val h = Harness()

      h.service.update(authenticated, h.session)
      h.debounce.release(0)
      h.drainUi()
      h.popups.get() shouldBe 0

      h.service.update(unauthenticated, h.session)
      h.debounce.release(1)
      h.drainUi()
      h.popups.get() shouldBe 1

      h.service.update(unauthenticated, h.session)
      h.debounce.release(2)
      h.drainUi()
      h.popups.get() shouldBe 1

      h.service.update(unrelatedOnly, h.session)
      h.debounce.release(3)
      h.drainUi()
      h.popups.get() shouldBe 1
    }
  }

  describe("deterministic races") {
    it("old session call paused after its entrance check: the new session's job is not cancelled") {
      val h = Harness()
      val old = h.session
      val next = LanguageServerSession()

      lateinit var a: Thread
      a = thread(start = false) { h.service.update(authenticated, old) }
      h.connection.pause = Pause(a, nth = 1)
      a.start()
      h.connection.pause!!.awaitEntered()

      h.connection.session = next
      val b = thread { h.service.update(unauthenticated, next) }
      awaitBlockedOnLock(b)
      h.connection.pause!!.resume.countDown()
      a.join(TimeUnit.SECONDS.toMillis(waitSeconds))
      b.join(TimeUnit.SECONDS.toMillis(waitSeconds))

      h.debounce.gates.size shouldBe 2
      h.debounce.release(1)

      verify(exactly = 1) { h.provider.update(any(), any(), any()) }
      verify(exactly = 1) { h.provider.update(unauthenticated, next, any()) }
      h.published shouldBe true
      h.drainUi()
      h.popups.get() shouldBe 1

      h.debounce.release(0)
      verify(exactly = 1) { h.provider.update(any(), any(), any()) }
    }

    it("same session: old job paused before the generation check resumes after the new job committed") {
      val h = Harness()

      h.service.update(unauthenticated, h.session)
      lateinit var t1: Thread
      t1 = thread(start = false) { h.debounce.release(0) }
      h.connection.pause = Pause(t1, nth = 1)
      t1.start()
      h.connection.pause!!.awaitEntered()

      h.service.update(authenticated, h.session)
      h.debounce.release(1)
      h.provider.authState shouldBe AuthState.AUTHENTICATED

      h.connection.pause!!.resume.countDown()
      t1.join(TimeUnit.SECONDS.toMillis(waitSeconds))

      verify(exactly = 1) { h.provider.update(any(), any(), any()) }
      verify(exactly = 1) { h.provider.update(authenticated, h.session, any()) }
      h.provider.authState shouldBe AuthState.AUTHENTICATED
      h.drainUi()
      h.popups.get() shouldBe 0

      h.service.update(unauthenticated, h.session)
      h.debounce.release(2)
      h.drainUi()
      h.popups.get() shouldBe 1
    }

    it("resetAuthenticatedState() during a commit waits for the lock; the outcome is the reset") {
      val h = Harness()

      h.service.update(unauthenticated, h.session)
      lateinit var t1: Thread
      t1 = thread(start = false) { h.debounce.release(0) }
      h.connection.pause = Pause(t1, nth = 2)
      t1.start()
      h.connection.pause!!.awaitEntered()

      val t2 = thread { h.service.resetAuthenticatedState() }
      awaitBlockedOnLock(t2)
      h.connection.pause!!.resume.countDown()
      t1.join(TimeUnit.SECONDS.toMillis(waitSeconds))
      t2.join(TimeUnit.SECONDS.toMillis(waitSeconds))

      verifyOrder {
        h.provider.update(unauthenticated, h.session, any())
        h.provider.reset(h.session, any())
      }
      h.provider.authState shouldBe AuthState.UNKNOWN
      h.published shouldBe false
      h.drainUi()
      h.popups.get() shouldBe 0

      h.service.update(unauthenticated, h.session)
      h.debounce.release(1)
      h.drainUi()
      h.popups.get() shouldBe 1
    }

    it("resetAuthenticatedState() before the job takes the lock stops the job at the generation check") {
      val h = Harness()

      h.service.update(unauthenticated, h.session)
      h.service.resetAuthenticatedState()
      h.debounce.release(0)

      verify(exactly = 0) { h.provider.update(any(), any(), any()) }
      h.uiQueue.size shouldBe 0
      h.published shouldBe false

      h.service.update(unauthenticated, h.session)
      h.debounce.release(1)
      h.drainUi()
      h.popups.get() shouldBe 1
    }

    it("resetForConnectionChange() after the job's connection re-check stops it at the generation check") {
      val h = Harness()
      val old = h.session

      h.service.update(unauthenticated, old)
      lateinit var t1: Thread
      t1 = thread(start = false) { h.debounce.release(0) }
      h.connection.pause = Pause(t1, nth = 1)
      t1.start()
      h.connection.pause!!.awaitEntered()

      h.connection.session = null
      h.service.resetForConnectionChange()
      h.connection.pause!!.resume.countDown()
      t1.join(TimeUnit.SECONDS.toMillis(waitSeconds))

      verify(exactly = 0) { h.provider.update(any(), any(), any()) }
      verify(exactly = 1) { h.provider.reset(null, any()) }
      h.uiQueue.size shouldBe 0
      h.published shouldBe false

      val next = LanguageServerSession()
      h.connection.session = next
      h.service.update(unauthenticated, next)
      h.debounce.release(1)
      h.drainUi()
      h.popups.get() shouldBe 1
    }
  }

  describe("popup runnable re-check on the UI thread") {
    fun queuedPopup(): Harness {
      val h = Harness()
      h.service.update(unauthenticated, h.session)
      h.debounce.release(0)
      h.uiQueue.size shouldBe 1
      return h
    }

    it("shows once when nothing intervenes") {
      val h = queuedPopup()
      h.drainUi()
      h.popups.get() shouldBe 1
    }

    it("does not show after resetAuthenticatedState()") {
      val h = queuedPopup()
      h.service.resetAuthenticatedState()
      h.drainUi()
      h.popups.get() shouldBe 0
    }

    it("does not show after an authenticated notification committed") {
      val h = queuedPopup()
      h.service.update(authenticated, h.session)
      h.debounce.release(1)
      h.drainUi()
      h.popups.get() shouldBe 0
    }

    it("does not show after resetForConnectionChange()") {
      val h = queuedPopup()
      h.connection.session = null
      h.service.resetForConnectionChange()
      h.drainUi()
      h.popups.get() shouldBe 0
    }

    // Ruling R3: the popup follows the settled state, not the entrance generation. A same-value
    // notification accepted while the runnable waits for the UI thread must not lose the popup: its
    // own commit stops at the unchanged-state early return and would never queue another one.
    it("still shows once when a same-value notification was accepted before the UI ran") {
      val h = queuedPopup()
      h.service.update(unauthenticated, h.session)
      h.drainUi()
      h.popups.get() shouldBe 1

      h.debounce.release(1)
      h.drainUi()
      h.popups.get() shouldBe 1
    }
  }
})
