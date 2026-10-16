package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.google.gson.JsonSyntaxException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.eclipse.equinox.security.storage.StorageException
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Spec for the single-flight first load: within one attempt only one thread reads secure storage, other
 * non-UI callers wait for it and answer from the published state, and the UI thread never waits (it would
 * deadlock with the master-password prompt's `syncExec`). Split from [OAuthTokenProviderTest] for its size.
 */
class OAuthTokenProviderSingleFlightTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  afterEach { clearAllMocks() }

  describe("single-flight first load (A)") {
    it("S1: concurrent background callers share one read and all get the stored token") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { validToken("stored") }
      val loader = f.startLoader { f.provider.getToken() }

      val waiters = List(WAITERS) { Caller { f.provider.getToken() }.start() }
      waitingInLoad(waiters) shouldBe true
      f.release.countDown()

      (waiters + loader).forEach { it.finishes() shouldBe true }
      (waiters + loader).map { it.result.get() } shouldContainOnly listOf("stored")
      f.reads.get() shouldBe 1
    }

    it("S2: waiters on a failed read do not read, report no token and the failure notifies once") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { throw storageFailure() }
      val loader = f.startLoader { f.provider.hasToken() }

      val waiters = List(WAITERS) { Caller { f.provider.hasToken() }.start() }
      waitingInLoad(waiters) shouldBe true
      f.release.countDown()

      (waiters + loader).forEach { it.finishes() shouldBe true }
      (waiters + loader).map { it.result.get() } shouldContainOnly listOf(false)
      f.reads.get() shouldBe 1
      f.notifications shouldBe listOf(LOAD_FAILURE_MESSAGE)
      f.authType.get() shouldBe TokenProviderType.OAUTH.name
    }

    it("S3: a UI-thread caller during a background read answers no token at once without reading") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { validToken("stored") }
      val loader = f.startLoader { f.provider.hasToken() }

      val ui = Caller { f.provider.hasToken() }
      f.uiThread.set(ui.thread)
      ui.start()

      // Finishes while the read is still held: it never waited for the loader.
      ui.finishes() shouldBe true
      loader.thread.isAlive shouldBe true
      ui.result.get() shouldBe false
      f.reads.get() shouldBe 1

      f.release.countDown()
      loader.finishes() shouldBe true
      loader.result.get() shouldBe true
    }

    it("S4: a background caller waits for the UI thread's read and gets the token") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { validToken("stored") }
      val loader = Caller { f.provider.hasToken() }
      f.uiThread.set(loader.thread)
      loader.start()
      f.reading.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true

      val waiter = Caller { f.provider.getToken() }.start()
      waitingInLoad(listOf(waiter)) shouldBe true
      f.release.countDown()

      waiter.finishes() shouldBe true
      loader.finishes() shouldBe true
      waiter.result.get() shouldBe "stored"
      f.reads.get() shouldBe 1
    }

    it("S5: a re-entrant call on the loader thread returns at once") {
      val f = SingleFlightFixture()
      val inner = AtomicReference<Boolean?>()
      every { f.storage.getOAuthToken() } answers {
        // Like a runnable from the prompt's nested event loop asking for the token on the same thread.
        if (f.reads.incrementAndGet() == 1) inner.set(f.provider.hasToken())
        validToken("stored")
      }

      f.provider.hasToken() shouldBe true

      inner.get() shouldBe false
      f.reads.get() shouldBe 1
    }

    it("S6: an interrupted waiter returns, keeps its interrupt flag and does not read") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { validToken("stored") }
      val loader = f.startLoader { f.provider.hasToken() }

      val waiter = Caller { f.provider.getToken() }.start()
      waitingInLoad(listOf(waiter)) shouldBe true
      waiter.thread.interrupt()

      waiter.finishes() shouldBe true
      loader.thread.isAlive shouldBe true
      waiter.result.get() shouldBe ""
      waiter.interruptedAfter.get() shouldBe true
      f.reads.get() shouldBe 1

      f.release.countDown()
      loader.finishes() shouldBe true
    }

    it("S7: an unexpected exception from the read releases the waiters") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { throw JsonSyntaxException("corrupt") }
      val loader = f.startLoader { f.provider.hasToken() }

      val waiters = List(WAITERS) { Caller { f.provider.hasToken() }.start() }
      waitingInLoad(waiters) shouldBe true
      f.release.countDown()

      (waiters + loader).forEach { it.finishes() shouldBe true }
      (waiters + loader).map { it.result.get() } shouldContainOnly listOf(false)
      f.reads.get() shouldBe 1
      f.authType.get() shouldBe TokenProviderType.PAT.name
    }

    it("S7: an Error from the read releases the waiters") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { throw LinkageError("boom") }
      val loader = f.startLoader { f.provider.getToken() }

      val waiters = List(WAITERS) { Caller { f.provider.getToken() }.start() }
      waitingInLoad(waiters) shouldBe true
      f.release.countDown()

      (waiters + loader).forEach { it.finishes() shouldBe true }
      loader.error.get().shouldBeInstanceOf<LinkageError>()
      waiters.map { it.result.get() } shouldContainOnly listOf("")
      waiters.map { it.error.get() } shouldContainOnly listOf(null)
      f.reads.get() shouldBe 1
    }

    it("S8: a sign-in releases the waiters with the new token before the stale read ends") {
      val f = SingleFlightFixture()
      f.holdFirstReadThen { throw storageFailure() }
      val loader = f.startLoader { f.provider.hasToken() }

      val waiters = List(WAITERS) { Caller { f.provider.getToken() }.start() }
      waitingInLoad(waiters) shouldBe true
      f.provider.updateToken(validToken("signed-in"))

      // The stale read is still held: the sign-in itself released them.
      waiters.forEach { it.finishes() shouldBe true }
      loader.thread.isAlive shouldBe true
      waiters.map { it.result.get() } shouldContainOnly listOf("signed-in")

      f.release.countDown()
      loader.finishes() shouldBe true
      f.notifications shouldBe emptyList()
      f.provider.getToken() shouldBe "signed-in"
      f.authType.get() shouldBe TokenProviderType.OAUTH.name
      f.reads.get() shouldBe 1
    }
  }

  describe("re-read after an empty or corrupt load (F3)") {
    it("F3a: an empty read switches to PAT; selecting OAuth again reads storage again") {
      val f = SingleFlightFixture()
      every { f.storage.getOAuthToken() } answers {
        if (f.reads.incrementAndGet() == 1) null else validToken("stored")
      }

      f.provider.hasToken() shouldBe false
      f.authType.get() shouldBe TokenProviderType.PAT.name

      f.authType.set(TokenProviderType.OAUTH.name)
      f.provider.hasToken() shouldBe true
      f.reads.get() shouldBe 2
    }

    it("F3b: a corrupt read switches to PAT; selecting OAuth again reads storage again") {
      val f = SingleFlightFixture()
      every { f.storage.getOAuthToken() } answers {
        if (f.reads.incrementAndGet() == 1) throw JsonSyntaxException("corrupt")
        validToken("stored")
      }

      f.provider.hasToken() shouldBe false
      f.authType.get() shouldBe TokenProviderType.PAT.name

      f.authType.set(TokenProviderType.OAUTH.name)
      f.provider.hasToken() shouldBe true
      f.reads.get() shouldBe 2
    }
  }

  describe("re-read after a loaded token is dropped") {
    it("D1: a token dropped while PAT is selected is read again once OAuth is selected again") {
      val f = SingleFlightFixture()
      every { f.storage.getOAuthToken() } answers {
        if (f.reads.incrementAndGet() == 1) expiredToken("stored") else validToken("again")
      }
      f.provider.hasToken() shouldBe true

      // The user selects PAT; the expired token is then dropped instead of refreshed (N1).
      f.authType.set(TokenProviderType.PAT.name)
      f.provider.getToken() shouldBe ""

      f.authType.set(TokenProviderType.OAUTH.name)
      f.provider.getToken() shouldBe "again"
      f.reads.get() shouldBe 2
    }

    it("D2: after a rejected refresh, selecting OAuth again reads storage again") {
      val f = SingleFlightFixture()
      every { f.storage.getOAuthToken() } answers {
        if (f.reads.incrementAndGet() == 1) expiredToken("dead") else validToken("again")
      }
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Rejected("invalid_grant")
      f.provider.getToken() shouldBe ""
      f.authType.get() shouldBe TokenProviderType.PAT.name

      f.authType.set(TokenProviderType.OAUTH.name)
      f.provider.getToken() shouldBe "again"
      f.reads.get() shouldBe 2
    }
  }
})

private const val WAITERS = 3
private const val AWAIT_TIMEOUT_MS = 5_000L
private const val POLL_MS = 5L
private const val EXPIRES_IN_SECONDS = 3600
private const val LOAD_FAILURE_MESSAGE =
  "Could not read the GitLab OAuth token from secure storage. Restart Eclipse or sign in again."
private val NOW: Instant = Instant.ofEpochSecond(1_000_000_000L)

/** What Equinox throws when the master-password prompt is cancelled. */
private fun storageFailure() = StorageException(StorageException.NO_PASSWORD, "cancelled")

private fun validToken(name: String) =
  GitLabAuthorizationToken(name, "refresh-$name", EXPIRES_IN_SECONDS, NOW.epochSecond)

private fun expiredToken(name: String) =
  GitLabAuthorizationToken(name, "refresh-$name", EXPIRES_IN_SECONDS, NOW.epochSecond - EXPIRES_IN_SECONDS)

/**
 * A provider wired to fakes: a stateful authentication type (OAuth at first), captured notifications, a
 * storage read counter and an injected UI thread ([uiThread]; none by default).
 */
private class SingleFlightFixture {
  val authType = AtomicReference(TokenProviderType.OAUTH.name)
  val notifications = CopyOnWriteArrayList<String>()
  val reads = AtomicInteger()
  val reading = CountDownLatch(1)
  val release = CountDownLatch(1)
  val uiThread = AtomicReference<Thread?>(null)
  val prefs = mockk<ScopedPreferenceStore>()
  val lsConfig = mockk<GitLabLanguageServerConfigurationService>()
  val storage = mockk<OAuthSecretStorage>()
  val service = mockk<GitLabOAuthService>()
  val provider = OAuthTokenProvider(
    lsConfig,
    prefs,
    storage,
    oAuthService = { service },
    clock = { NOW },
    notify = { notifications.add(it) },
    isUiThread = { Thread.currentThread() === uiThread.get() },
  )

  init {
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } answers { authType.get() }
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } answers {
      authType.set(secondArg())
    }
    every { lsConfig.sendConfiguration() } just Runs
    every { storage.setOAuthToken(any()) } just Runs
  }

  /**
   * The first storage read signals [reading], waits for [release] (bounded), then ends with [outcome]; any
   * later read ends with [outcome] at once, so a caller that wrongly reads does not block and is counted.
   */
  fun holdFirstReadThen(outcome: () -> GitLabAuthorizationToken?) {
    every { storage.getOAuthToken() } answers {
      if (reads.incrementAndGet() == 1) {
        reading.countDown()
        release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }
      outcome()
    }
  }

  /** Starts [task] on a background thread and returns once it is inside the held storage read. */
  fun <T> startLoader(task: () -> T): Caller<T> {
    val loader = Caller(task).start()
    reading.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
    return loader
  }
}

/** Runs [task] on its own thread and records its result or throwable, and its interrupt flag afterwards. */
private class Caller<T>(task: () -> T) {
  val result = AtomicReference<T?>()
  val error = AtomicReference<Throwable?>()
  val interruptedAfter = AtomicBoolean()
  val thread = Thread {
    try {
      result.set(task())
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
      error.set(t)
    }
    interruptedAfter.set(Thread.currentThread().isInterrupted)
  }

  fun start(): Caller<T> = apply { thread.start() }

  /** Joins with a timeout; whether the thread has finished. */
  fun finishes(): Boolean {
    thread.join(AWAIT_TIMEOUT_MS)
    return !thread.isAlive
  }
}

/** Waits until every caller is parked inside the first load (waiting for the loader, not reading). */
private fun waitingInLoad(callers: List<Caller<*>>): Boolean = awaitUntil {
  callers.all { c ->
    c.thread.state == Thread.State.WAITING &&
      c.thread.stackTrace.any { it.methodName.startsWith("loadCachedIfAbsent") }
  }
}

/** Polls [condition] until it holds or [AWAIT_TIMEOUT_MS] passes; returns whether it held. */
private fun awaitUntil(condition: () -> Boolean): Boolean {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS)
  while (!condition()) {
    if (System.nanoTime() > deadline) return false
    Thread.sleep(POLL_MS)
  }
  return true
}
