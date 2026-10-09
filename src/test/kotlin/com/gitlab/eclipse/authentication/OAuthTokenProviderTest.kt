package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.delay
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class OAuthTokenProviderTest : DescribeSpec({
  val oAuthService = mockk<GitLabOAuthService>()
  val languageServerConfigurationService = mockk<GitLabLanguageServerConfigurationService>()
  val scopedPreferenceStore = mockk<ScopedPreferenceStore>()
  val oauthSecretStorage = mockk<OAuthSecretStorage>()
  val tokenProvider = OAuthTokenProvider(languageServerConfigurationService, scopedPreferenceStore, oauthSecretStorage)

  extensions(LoggingKotestExtension)

  startKoin {
    modules(
      module {
        single<ScopedPreferenceStore> { scopedPreferenceStore }
        single<GitLabLanguageServerConfigurationService> { languageServerConfigurationService }
        single<GitLabOAuthService> { oAuthService }
      }
    )
  }

  beforeEach {
    every { languageServerConfigurationService.sendConfiguration() } just Runs
    every {
      scopedPreferenceStore.setValue(PreferenceConstants.AUTHENTICATION_TYPE, TokenProviderType.OAUTH.name)
    } just Runs
    every { oauthSecretStorage.setOAuthToken(any()) } just Runs
    // The refresh path re-checks the authentication type under the refresh lock (design §8, N1).
    every {
      scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE)
    } returns TokenProviderType.OAUTH.name
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("getToken") {
    describe("when a token is set") {
      val tokenValue = "access_token_value"
      val mockToken = GitLabAuthorizationToken(
        accessToken = tokenValue,
        refreshToken = "refresh_token",
        expiresIn = 3600,
        createdAt = Instant.now().epochSecond
      )

      beforeTest {
        tokenProvider.updateToken(mockToken)
      }

      it("should return the token value") {
        tokenProvider.getToken() shouldBe tokenValue
      }

      it("should return the new token value when token is updated") {
        val newTokenValue = "new-access-token"
        val newMockToken = GitLabAuthorizationToken(
          accessToken = newTokenValue,
          refreshToken = "refresh_token",
          expiresIn = 3600,
          createdAt = Instant.now().epochSecond
        )
        tokenProvider.updateToken(newMockToken)

        assertEquals(newTokenValue, tokenProvider.getToken())
      }
    }
  }

  describe("updateToken") {
    it("should update the token and set authentication type to OAUTH") {
      val tokenValue = "test_token"
      val mockToken = GitLabAuthorizationToken(
        accessToken = tokenValue,
        refreshToken = "refresh_token",
        expiresIn = 3600,
        createdAt = Instant.now().epochSecond
      )

      tokenProvider.updateToken(mockToken)

      verify {
        scopedPreferenceStore.setValue(PreferenceConstants.AUTHENTICATION_TYPE, TokenProviderType.OAUTH.name)
      }
      verify { languageServerConfigurationService.sendConfiguration() }

      tokenProvider.getToken() shouldBe tokenValue
    }
  }

  describe("refreshToken") {
    it("token returns valid token when not expired") {
      val validToken = GitLabAuthorizationToken("valid_token", "refresh_token", 3600, Instant.now().epochSecond)

      tokenProvider.updateToken(validToken)

      assertEquals("valid_token", tokenProvider.getToken())
    }

    it("refreshes token when expired") {
      val oldToken = GitLabAuthorizationToken(
        "old_token",
        "refresh_token",
        3600,
        Instant.now().epochSecond - 3601
      )
      val newToken = GitLabAuthorizationToken(
        "new_token",
        "refresh_token",
        3600,
        Instant.now().epochSecond
      )

      every {
        oAuthService.refreshToken(oldToken.refreshToken)
      } returns RefreshOutcome.Refreshed(newToken)

      tokenProvider.updateToken(oldToken)

      assertEquals("new_token", tokenProvider.getToken())
    }
  }

  describe("startTokenRefreshTimer") {
    val timerRefreshInSeconds = 1

    afterEach { clearAllMocks() }
    afterSpec { unmockkAll() }

    it("timer shouldn't start when the token is null") {
      every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns TokenProviderType.OAUTH.name
      tokenProvider.updateToken(null)
      tokenProvider.startTokenRefreshTimer(timerRefreshInSeconds)

      verify(exactly = 0) {
        oAuthService.refreshToken(any())
      }
    }

    it("token refreshes periodically when OAuth is enabled") {
      val realScheduler = Executors.newScheduledThreadPool(1)
      tokenProvider.scheduler = realScheduler

      val newToken =
        GitLabAuthorizationToken("new_token", "refresh_token", 3600, Instant.now().epochSecond)

      every { oAuthService.refreshToken(any()) } returns RefreshOutcome.Refreshed(newToken)
      every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns TokenProviderType.OAUTH.name

      val expiredToken = GitLabAuthorizationToken(
        "expired_token",
        "refresh_token",
        3600,
        Instant.now().epochSecond.minus(5000)
      )

      // Make sure the token provider has an existing token that is expired
      tokenProvider.updateToken(expiredToken)

      tokenProvider.startTokenRefreshTimer(timerRefreshInSeconds)

      delay(2.seconds)

      assertEquals("new_token", tokenProvider.getToken())

      verify(exactly = 1) { oAuthService.refreshToken(any()) }

      realScheduler.shutdownNow()
    }
  }

  describe("refresh coordination (design §8, §9, §11)") {
    it("T6: concurrent callers holding an expired token trigger exactly one refresh") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      val entered = AtomicInteger()
      val workers = CopyOnWriteArrayList<Thread>()
      every { f.service.refreshToken(any()) } answers {
        entered.incrementAndGet()
        // Hold the refresh until every other caller is parked on the lock (or, without the lock, has
        // entered the refresh itself), so the contention is guaranteed rather than merely likely.
        awaitUntil { blockedIn(workers, "refreshIfExpired") + entered.get() >= WORKERS }
        RefreshOutcome.Refreshed(valid("new"))
      }

      val results = runConcurrently(WORKERS, workers) { f.provider.getToken() }

      verify(exactly = 1) { f.service.refreshToken(any()) }
      results shouldContainOnly listOf("new")
    }

    it("T6b: concurrent first loads publish the cached token once and never overwrite the refreshed one") {
      val f = Fixture()
      val stale = expired("stale")
      every { f.storage.getOAuthToken() } returns stale
      val entered = AtomicInteger()
      val workers = CopyOnWriteArrayList<Thread>()
      every { f.service.refreshToken(any()) } answers {
        entered.incrementAndGet()
        awaitUntil { blockedIn(workers, "refreshIfExpired") + entered.get() >= WORKERS }
        RefreshOutcome.Refreshed(valid("new"))
      }

      val results = runConcurrently(WORKERS, workers) { f.provider.getToken() }

      verify(exactly = 1) { f.service.refreshToken(any()) }
      results shouldContainOnly listOf("new")
      f.provider.getToken() shouldBe "new"
      verify(exactly = 0) { f.storage.setOAuthToken(stale) }
      verify(exactly = 1) { f.storage.setOAuthToken(valid("new")) }
    }

    it("T7: a transient failure keeps OAuth, notifies once and waits for the back-off before retrying") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      clearMocks(f.prefs, answers = false)
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Transient("SocketTimeoutException")

      f.provider.getToken() shouldBe "old"

      verify(exactly = 0) {
        f.prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, TokenProviderType.PAT.name)
      }
      f.authType.get() shouldBe TokenProviderType.OAUTH.name
      f.notifications shouldBe listOf(TRANSIENT_MESSAGE)

      f.advance(10)
      f.provider.getToken() shouldBe "old"
      verify(exactly = 1) { f.service.refreshToken(any()) }

      f.advance(21)
      f.provider.getToken() shouldBe "old"
      verify(exactly = 2) { f.service.refreshToken(any()) }
      f.notifications shouldBe listOf(TRANSIENT_MESSAGE)
    }

    it("T8: consecutive transient failures notify once; a success re-arms the notification") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      every { f.service.refreshToken(any()) } returnsMany listOf(
        RefreshOutcome.Transient("IOException"),
        RefreshOutcome.Transient("IOException"),
        // Still expired, so the next call refreshes again and can fail again.
        RefreshOutcome.Refreshed(expired("mid")),
        RefreshOutcome.Transient("IOException"),
      )

      f.provider.getToken()
      f.advance(31)
      f.provider.getToken()
      f.notifications shouldBe listOf(TRANSIENT_MESSAGE)

      f.advance(31)
      f.provider.getToken() shouldBe "mid"
      f.provider.getToken() shouldBe "mid"

      verify(exactly = 4) { f.service.refreshToken(any()) }
      f.notifications shouldBe listOf(TRANSIENT_MESSAGE, TRANSIENT_MESSAGE)
    }

    it("T9: a rejection asks to re-authenticate, switches to PAT and stops refreshing") {
      val f = Fixture()
      every { f.storage.getOAuthToken() } returns expired("dead")
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Rejected("invalid_grant")

      f.provider.getToken() shouldBe ""

      f.notifications shouldBe listOf(REAUTH_MESSAGE)
      f.authType.get() shouldBe TokenProviderType.PAT.name

      f.provider.getToken() shouldBe ""
      f.provider.runScheduledRefresh()

      verify(exactly = 1) { f.service.refreshToken(any()) }
      verify(exactly = 1) { f.storage.getOAuthToken() }
      verify(exactly = 0) { f.storage.setOAuthToken(any()) }
      f.notifications shouldBe listOf(REAUTH_MESSAGE)
    }

    it("T10: an exception from the refresh leaves no lock or state behind") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      every { f.service.refreshToken(any()) } throws RuntimeException("boom") andThen
        RefreshOutcome.Refreshed(valid("new"))

      shouldThrow<RuntimeException> { f.provider.getToken() }

      // Another thread must be able to take the lock and refresh.
      val result = AtomicReference<String>()
      val thread = Thread { result.set(f.provider.getToken()) }.apply { start() }
      thread.join(JOIN_TIMEOUT_MS)
      thread.isAlive shouldBe false
      result.get() shouldBe "new"
      verify(exactly = 2) { f.service.refreshToken(any()) }
    }

    it("T11: callers waiting on an in-flight refresh that fails transiently do not refresh themselves") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      val entered = AtomicInteger()
      val workers = CopyOnWriteArrayList<Thread>()
      every { f.service.refreshToken(any()) } answers {
        entered.incrementAndGet()
        awaitUntil { blockedIn(workers, "refreshIfExpired") + entered.get() >= T11_WORKERS }
        RefreshOutcome.Transient("SocketTimeoutException")
      }

      val results = runConcurrently(T11_WORKERS, workers) { f.provider.getToken() }

      verify(exactly = 1) { f.service.refreshToken(any()) }
      results shouldContainOnly listOf("old")
      f.notifications shouldBe listOf(TRANSIENT_MESSAGE)
    }

    it("T12: notifications and sendConfiguration run outside the refresh lock") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      val innerResults = CopyOnWriteArrayList<String?>()

      // Starts, on another thread, a getToken() that itself needs the lock, and waits for it.
      fun probeFromAnotherThread(action: () -> String) {
        val result = AtomicReference<String?>()
        val thread = Thread { result.set(action()) }.apply { start() }
        thread.join(JOIN_TIMEOUT_MS)
        innerResults.add(if (thread.isAlive) null else result.get())
      }

      // Transient notification: the probe advances past the back-off, so it really refreshes.
      every { f.service.refreshToken(any()) } returnsMany listOf(
        RefreshOutcome.Transient("IOException"),
        RefreshOutcome.Refreshed(valid("after-transient")),
      )
      val probedOnce = AtomicBoolean()
      f.onNotify = {
        if (probedOnce.compareAndSet(false, true)) {
          f.advance(31)
          probeFromAnotherThread { f.provider.getToken() }
        }
      }
      f.provider.getToken()
      innerResults shouldBe listOf("after-transient")

      // sendConfiguration after a refresh: the refreshed token is still expired, so the probe refreshes.
      f.provider.updateToken(expired("old2"))
      innerResults.clear()
      every { f.service.refreshToken(any()) } returnsMany listOf(
        RefreshOutcome.Refreshed(expired("expired-again")),
        RefreshOutcome.Refreshed(valid("after-send")),
      )
      val sendProbed = AtomicBoolean()
      every { f.lsConfig.sendConfiguration() } answers {
        if (sendProbed.compareAndSet(false, true)) probeFromAnotherThread { f.provider.getToken() }
      }
      f.provider.getToken()
      innerResults shouldBe listOf("after-send")

      // sendConfiguration from updateToken.
      innerResults.clear()
      sendProbed.set(false)
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Refreshed(valid("after-update"))
      f.provider.updateToken(expired("old3"))
      innerResults shouldBe listOf("after-update")

      // Re-authentication notification: the probe takes the lock through updateToken.
      every { f.lsConfig.sendConfiguration() } just Runs
      f.provider.updateToken(expired("old4"))
      innerResults.clear()
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Rejected("invalid_grant")
      f.onNotify = {
        probeFromAnotherThread {
          f.provider.updateToken(valid("re-auth"))
          "updated"
        }
      }
      f.provider.getToken()
      innerResults shouldBe listOf("updated")
    }

    it("T12b: an exception in one scheduled run does not stop the next one") {
      val f = Fixture()
      val scheduler = Executors.newSingleThreadScheduledExecutor()
      f.provider.scheduler = scheduler
      try {
        f.provider.updateToken(expired("old"))
        val runs = CountDownLatch(2)
        every { f.service.refreshToken(any()) } answers {
          runs.countDown()
          check(runs.count != 1L) { "first run fails" }
          RefreshOutcome.Transient("IOException")
        }

        f.provider.scheduleRefresh(SCHEDULE_PERIOD_MS)

        runs.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      } finally {
        scheduler.shutdownNow()
      }
    }

    it("N1: a stale token re-published by a late first load after a rejection is dropped, not refreshed") {
      val f = Fixture()
      val dead = expired("dead")
      val lateLoader = AtomicReference<Thread>()
      val lateLoaderReading = CountDownLatch(1)
      val releaseLateLoader = CountDownLatch(1)
      every { f.storage.getOAuthToken() } answers {
        if (Thread.currentThread() === lateLoader.get()) {
          lateLoaderReading.countDown()
          releaseLateLoader.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        dead
      }
      every { f.service.refreshToken(any()) } returns RefreshOutcome.Rejected("invalid_grant")

      // The late loader passes the "absent and OAuth enabled" check, then stalls in the storage read.
      val loader = Thread { f.provider.loadCachedIfAbsent() }
      lateLoader.set(loader)
      loader.start()
      lateLoaderReading.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true

      f.provider.getToken() shouldBe ""
      f.authType.get() shouldBe TokenProviderType.PAT.name

      // Now it publishes the dead token again (its CAS from null succeeds).
      releaseLateLoader.countDown()
      loader.join(JOIN_TIMEOUT_MS)
      loader.isAlive shouldBe false

      f.provider.getToken() shouldBe ""
      verify(exactly = 1) { f.service.refreshToken(any()) }
      f.notifications shouldBe listOf(REAUTH_MESSAGE)
    }

    it("T13u: updateToken during a refresh waits for it and wins as the later writer") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      val inRefresh = CountDownLatch(1)
      val releaseRefresh = CountDownLatch(1)
      every { f.service.refreshToken(any()) } answers {
        inRefresh.countDown()
        releaseRefresh.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        RefreshOutcome.Refreshed(valid("refreshed"))
      }

      val refresher = Thread { f.provider.getToken() }.apply { start() }
      inRefresh.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      val updater = Thread { f.provider.updateToken(valid("from-callback")) }.apply { start() }
      awaitUntil { blockedIn(listOf(updater), "updateToken") == 1 } shouldBe true
      releaseRefresh.countDown()
      refresher.join(JOIN_TIMEOUT_MS)
      updater.join(JOIN_TIMEOUT_MS)

      f.provider.getToken() shouldBe "from-callback"
    }
  }
})

private const val WORKERS = 10
private const val T11_WORKERS = 6
private const val JOIN_TIMEOUT_MS = 1_000L
private const val AWAIT_TIMEOUT_MS = 5_000L
private const val SCHEDULE_PERIOD_MS = 50L
private const val POLL_MS = 5L
private const val TRANSIENT_MESSAGE = "Could not refresh the GitLab OAuth token. Retrying automatically."
private const val REAUTH_MESSAGE = "Failed to refresh the OAuth token. Please re-authenticate."
private val T0: Instant = Instant.ofEpochSecond(1_000_000_000L)

/** Expired at [T0]: created one lifetime ago (the provider also subtracts its 120 s buffer). */
private fun expired(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, T0.epochSecond - 3600)

private fun valid(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, T0.epochSecond)

/** A provider wired to fakes: a stateful authentication type, an injected clock and captured notifications. */
private class Fixture {
  val authType = AtomicReference(TokenProviderType.OAUTH.name)
  val now = AtomicReference(T0)
  val notifications = CopyOnWriteArrayList<String>()

  @Volatile var onNotify: (String) -> Unit = {}
  val prefs = mockk<ScopedPreferenceStore>()
  val lsConfig = mockk<GitLabLanguageServerConfigurationService>()
  val storage = mockk<OAuthSecretStorage>()
  val service = mockk<GitLabOAuthService>()
  val provider = OAuthTokenProvider(
    lsConfig,
    prefs,
    storage,
    oAuthService = { service },
    clock = { now.get() },
    notify = { message ->
      notifications.add(message)
      onNotify(message)
    },
  )

  init {
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } answers { authType.get() }
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } answers {
      authType.set(secondArg())
    }
    every { lsConfig.sendConfiguration() } just Runs
    every { storage.setOAuthToken(any()) } just Runs
  }

  fun advance(seconds: Long) {
    now.updateAndGet { it.plusSeconds(seconds) }
  }
}

/** Runs [task] on [count] threads released together; returns their results once all have finished. */
private fun runConcurrently(count: Int, threads: MutableList<Thread>, task: () -> String): List<String?> {
  val start = CountDownLatch(1)
  val results = arrayOfNulls<String>(count)
  repeat(count) { i ->
    threads.add(
      Thread {
        start.await()
        results[i] = task()
      }
    )
  }
  threads.forEach { it.start() }
  start.countDown()
  threads.forEach { it.join(AWAIT_TIMEOUT_MS) }
  check(threads.none { it.isAlive }) { "workers did not finish" }
  return results.toList()
}

/**
 * How many of [threads] are blocked on a monitor inside [method] (i.e. waiting for a `synchronized` lock).
 * Threads are identified by the top stack frame's method name (`refreshIfExpired` / `updateToken`), so a
 * rename of those functions must be mirrored here, or the gates stop forcing the race.
 */
private fun blockedIn(threads: Collection<Thread>, method: String): Int =
  threads.count { t ->
    t.state == Thread.State.BLOCKED && t.stackTrace.firstOrNull()?.methodName?.startsWith(method) == true
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
