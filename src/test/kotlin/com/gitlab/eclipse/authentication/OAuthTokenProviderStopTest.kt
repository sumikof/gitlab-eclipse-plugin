package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.equinox.security.storage.StorageException
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.write

/**
 * Spec for effects queued before [OAuthTokenProvider.stopTokenRefreshTimer] (design §6): a notice whose UI turn
 * comes after the stop is not shown, a configuration send whose check runs after the stop is not sent, and
 * token persistence carries on (the refresh token is single use). Split from [OAuthTokenProviderTest] for its
 * size.
 */
class OAuthTokenProviderStopTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  afterEach { clearAllMocks() }

  describe("queued notices (O1, O6)") {
    it("O1: a transient-failure notice whose UI turn runs after the stop is not shown") {
      val f = StopFixture()
      f.dispatchTransientNotice()

      f.provider.stopTokenRefreshTimer()
      f.runPendingNotices()

      f.shown shouldBe emptyList()
    }

    it("O1: a transient-failure notice whose UI turn runs before the stop is shown") {
      val f = StopFixture()
      f.dispatchTransientNotice()

      f.runPendingNotices()
      f.provider.stopTokenRefreshTimer()

      f.shown shouldBe listOf(TRANSIENT_MESSAGE)
    }

    it("O1: a re-authentication notice whose UI turn runs after the stop is not shown") {
      val f = StopFixture()
      f.provider.updateToken(expired("old"))
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Rejected("invalid_grant")
      f.provider.getToken()
      f.pendingNotices shouldHaveSize 1

      f.provider.stopTokenRefreshTimer()
      f.runPendingNotices()

      f.shown shouldBe emptyList()
    }

    it("O1: a load-failure notice whose UI turn runs after the stop is not shown") {
      val f = StopFixture()
      f.dispatchLoadFailureNotice()

      f.provider.stopTokenRefreshTimer()
      f.runPendingNotices()

      f.shown shouldBe emptyList()
    }

    it("O1: a load-failure notice whose UI turn runs before the stop is shown") {
      val f = StopFixture()
      f.dispatchLoadFailureNotice()

      f.runPendingNotices()
      f.provider.stopTokenRefreshTimer()

      f.shown shouldBe listOf(LOAD_FAILURE_MESSAGE)
    }

    it("O6: a load-failure notice is not shown when a sign-in happened while it was queued") {
      val f = StopFixture()
      f.dispatchLoadFailureNotice()

      f.provider.updateToken(valid("signed-in"))
      f.runPendingNotices()

      f.shown shouldBe emptyList()
    }

    it("a stop waits for a notice being shown on the UI thread, and a notice after it shows nothing") {
      val f = StopFixture()
      f.dispatchLoadFailureNotice()
      val (message, gate) = f.pendingNotices.single()
      val showing = CountDownLatch(1)
      val release = CountDownLatch(1)

      val ui = Thread {
        gate {
          showing.countDown()
          release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          f.shown += message
        }
      }.apply { start() }
      showing.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      val stop = Thread { f.provider.stopTokenRefreshTimer() }.apply { start() }
      try {
        // The stop cannot complete while the notice is being shown.
        stop.join(STOP_BLOCKED_MS)
        stop.isAlive shouldBe true
      } finally {
        release.countDown()
        ui.join(AWAIT_TIMEOUT_MS)
        stop.join(AWAIT_TIMEOUT_MS)
      }
      stop.isAlive shouldBe false
      f.shown shouldBe listOf(LOAD_FAILURE_MESSAGE)

      // The same queued notice run again after the stop returned shows nothing.
      gate { f.shown += message }
      f.shown shouldBe listOf(LOAD_FAILURE_MESSAGE)
    }
  }

  describe("the notice's UI-thread gate never waits (R-1)") {
    it("a notice run while the stop holds the write lock returns at once and shows nothing") {
      val f = StopFixture()
      f.dispatchTransientNotice()
      val (message, gate) = f.pendingNotices.single()
      val locked = CountDownLatch(1)
      val release = CountDownLatch(1)

      // Stands in for the stop's one-assignment write region, held open.
      val stop = Thread {
        f.provider.effectGate.write {
          locked.countDown()
          release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
      }.apply { start() }
      locked.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      val ui = Thread { gate { f.shown += message } }.apply { start() }
      try {
        ui.join(GATE_RETURNS_MS)
        ui.isAlive shouldBe false
      } finally {
        release.countDown()
        ui.join(AWAIT_TIMEOUT_MS)
        stop.join(AWAIT_TIMEOUT_MS)
      }
      f.shown shouldBe emptyList()
    }

    it("a notice run while a stop waits behind a dispatch does not wait, and shows before the stop returns") {
      val f = StopFixture()
      f.dispatchTransientNotice()
      val (message, gate) = f.pendingNotices.single()
      val dispatching = CountDownLatch(1)
      val release = CountDownLatch(1)
      // A sign-in's dispatch region holds the read lock while its send is being queued.
      every { f.lsConfig.sendConfigurationIf(any()) } answers {
        dispatching.countDown()
        release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }

      val dispatcher = Thread { f.provider.updateToken(valid("signed-in")) }.apply { start() }
      dispatching.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      val stop = Thread { f.provider.stopTokenRefreshTimer() }.apply { start() }
      val ui = Thread { gate { f.shown += message } }
      try {
        awaitQueued(f, stop)
        ui.start()
        // A writer-preferring read would wait behind the queued stop, and so for the dispatch.
        ui.join(GATE_RETURNS_MS)
        ui.isAlive shouldBe false
        f.shown shouldBe listOf(TRANSIENT_MESSAGE)
        stop.isAlive shouldBe true
      } finally {
        release.countDown()
        dispatcher.join(AWAIT_TIMEOUT_MS)
        ui.join(AWAIT_TIMEOUT_MS)
        stop.join(AWAIT_TIMEOUT_MS)
      }
      stop.isAlive shouldBe false

      gate { f.shown += message }
      f.shown shouldBe listOf(TRANSIENT_MESSAGE)
    }
  }

  describe("queued configuration sends (O2)") {
    it("O2: a send queued by a sign-in is current before the stop and not after it") {
      val f = StopFixture()
      f.provider.updateToken(valid("signed-in"))
      val isCurrent = f.pendingSends.single()

      isCurrent() shouldBe true
      f.provider.stopTokenRefreshTimer()
      isCurrent() shouldBe false
    }

    it("O2: a send queued by a refresh is current before the stop and not after it") {
      val f = StopFixture()
      f.provider.updateToken(expired("old"))
      f.pendingSends.clear()
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Refreshed(valid("new"))
      f.provider.getToken() shouldBe "new"
      val isCurrent = f.pendingSends.single()

      isCurrent() shouldBe true
      f.provider.stopTokenRefreshTimer()
      isCurrent() shouldBe false
    }
  }

  describe("persistence after the stop (O3, O4)") {
    it("O3: a refresh in flight at the stop still stores the new token, but sends and notifies nothing") {
      val f = StopFixture(deferEffects = false)
      f.provider.updateToken(expired("old"))
      f.effectiveSends.set(0)
      val inFlight = CountDownLatch(1)
      val release = CountDownLatch(1)
      every { f.service.refreshToken(any()) } answers {
        inFlight.countDown()
        release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        RefreshOutcome.Refreshed(valid("new"))
      }

      val run = Thread { f.provider.runScheduledRefresh() }.apply { start() }
      try {
        inFlight.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
        f.provider.stopTokenRefreshTimer()
      } finally {
        release.countDown()
        run.join(AWAIT_TIMEOUT_MS)
      }
      run.isAlive shouldBe false

      verify { f.storage.setOAuthToken(valid("new")) }
      f.provider.getToken() shouldBe "new"
      f.effectiveSends.get() shouldBe 0
      f.shown shouldBe emptyList()
    }

    it("O4: a sign-in after the stop stores and publishes the token but sends nothing") {
      val f = StopFixture(deferEffects = false)
      f.provider.stopTokenRefreshTimer()

      f.provider.updateToken(valid("late"))

      verify { f.storage.setOAuthToken(valid("late")) }
      f.authType.get() shouldBe TokenProviderType.OAUTH.name
      f.provider.getToken() shouldBe "late"
      f.effectiveSends.get() shouldBe 0
    }
  }

  describe("idempotence (O5)") {
    it("O5: stopping twice neither throws nor blocks") {
      val f = StopFixture()
      val failure = AtomicReference<Throwable?>()

      val stop = Thread {
        runCatching {
          f.provider.stopTokenRefreshTimer()
          f.provider.stopTokenRefreshTimer()
        }.onFailure { failure.set(it) }
      }.apply { start() }
      stop.join(AWAIT_TIMEOUT_MS)

      stop.isAlive shouldBe false
      failure.get() shouldBe null
      shouldNotThrowAny { f.provider.stopTokenRefreshTimer() }
    }
  }
})

private const val AWAIT_TIMEOUT_MS = 5_000L
private const val STOP_BLOCKED_MS = 200L
private const val GATE_RETURNS_MS = 2_000L
private const val POLL_MS = 10L
private const val TRANSIENT_MESSAGE = "Could not refresh the GitLab OAuth token. Retrying automatically."
private const val LOAD_FAILURE_MESSAGE =
  "Could not read the GitLab OAuth token from secure storage. Restart Eclipse or sign in again."
private val NOW: Instant = Instant.ofEpochSecond(1_000_000_000L)

private fun valid(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, NOW.epochSecond)

private fun expired(name: String) =
  GitLabAuthorizationToken(name, "refresh-$name", 3600, NOW.epochSecond - 3601)

/** Waits until [stop] is queued for [OAuthTokenProvider.effectGate]'s write lock. */
private fun awaitQueued(f: StopFixture, stop: Thread) {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS)
  while (!f.provider.effectGate.hasQueuedThread(stop)) {
    check(System.nanoTime() < deadline) { "the stop never queued for the write lock" }
    Thread.sleep(POLL_MS)
  }
}

/**
 * A provider wired to fakes. With [deferEffects], notices and configuration sends are captured instead of run:
 * [pendingNotices] holds each notice's gate (its UI turn, run later by [runPendingNotices]) and [pendingSends]
 * each send's `isCurrent` check (the queued coroutine's). Otherwise both run at once on the calling thread, and
 * [effectiveSends] counts the sends whose check held.
 */
private class StopFixture(private val deferEffects: Boolean = true) {
  val authType = AtomicReference(TokenProviderType.OAUTH.name)
  val shown = CopyOnWriteArrayList<String>()
  val pendingNotices = CopyOnWriteArrayList<Pair<String, (() -> Unit) -> Unit>>()
  val pendingSends = CopyOnWriteArrayList<() -> Boolean>()
  val effectiveSends = AtomicInteger()
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
    notify = { message, gate ->
      if (deferEffects) pendingNotices += message to gate else gate { shown += message }
    },
    // Headless: the default would load SWT's Display, which has no native library here.
    isUiThread = { false },
  )

  init {
    // A shut-down scheduler makes the timer registration in updateToken a no-op.
    provider.scheduler = ScheduledThreadPoolExecutor(1).also { it.shutdown() }
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } answers { authType.get() }
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } answers {
      authType.set(secondArg())
    }
    every { lsConfig.sendConfigurationIf(any()) } answers {
      val isCurrent = firstArg<() -> Boolean>()
      if (deferEffects) {
        pendingSends += isCurrent
      } else if (isCurrent()) {
        effectiveSends.incrementAndGet()
      }
    }
    every { storage.setOAuthToken(any()) } just Runs
  }

  /** Runs the queued notices' UI turns, in order, as the UI thread would. */
  fun runPendingNotices() {
    val queued = pendingNotices.toList()
    pendingNotices.clear()
    queued.forEach { (message, gate) -> gate { shown += message } }
  }

  /** A refresh fails transiently; its notice is dispatched (queued), not yet shown. */
  fun dispatchTransientNotice() {
    provider.updateToken(expired("old"))
    every { service.refreshToken(any()) } returns RefreshOutcome.Transient("IOException")
    provider.getToken()
    pendingNotices shouldHaveSize 1
  }

  /** The first load cannot read secure storage; its notice is dispatched (queued), not yet shown. */
  fun dispatchLoadFailureNotice() {
    every { storage.getOAuthToken() } throws StorageException(StorageException.NO_PASSWORD, "cancelled")
    provider.hasToken() shouldBe false
    pendingNotices shouldHaveSize 1
  }
}
