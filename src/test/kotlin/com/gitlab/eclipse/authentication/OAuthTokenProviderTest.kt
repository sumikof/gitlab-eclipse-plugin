package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

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
    it("R1: runs never read secure storage; they refresh a token once the normal path has published it") {
      val f = Fixture()
      val scheduler = RunCountingScheduler()
      f.provider.scheduler = scheduler
      try {
        every { f.storage.getOAuthToken() } returns expired("stored")
        every { f.service.refreshToken(any()) } returns RefreshOutcome.Refreshed(valid("new"))
        val sent = CountDownLatch(1)
        every { f.lsConfig.sendConfiguration() } answers { sent.countDown() }

        f.provider.startTokenRefreshTimer()

        // Nothing is loaded yet: several runs pass without touching secure storage (the first load stays
        // on the existing paths, so there is never a second concurrent master-password prompt).
        awaitUntil { scheduler.runs.get() >= RUNS } shouldBe true
        verify(exactly = 0) { f.storage.getOAuthToken() }
        verify(exactly = 0) { f.service.refreshToken(any()) }

        // The normal path (here hasToken, which loads without refreshing) publishes the expired token.
        f.provider.hasToken() shouldBe true

        sent.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
        verify(exactly = 1) { f.service.refreshToken("refresh-stored") }
        f.provider.getToken() shouldBe "new"
        verify(exactly = 1) { f.storage.getOAuthToken() }
        scheduler.isShutdown shouldBe false
      } finally {
        scheduler.stopAndAwait()
      }
    }

    it("R2: leaves a published valid token alone, then refreshes it on a run after it expires") {
      val f = Fixture()
      val scheduler = Executors.newSingleThreadScheduledExecutor()
      f.provider.scheduler = scheduler
      try {
        every { f.storage.getOAuthToken() } returns valid("stored")
        every { f.service.refreshToken(any()) } returns RefreshOutcome.Refreshed(validAt("new", AFTER_EXPIRY_S))
        val sent = CountDownLatch(1)
        every { f.lsConfig.sendConfiguration() } answers { sent.countDown() }

        f.provider.startTokenRefreshTimer()
        f.provider.getToken() shouldBe "stored"

        // Several runs see the published, still valid token.
        val readsAfterPublish = f.clockReads.get()
        awaitUntil { f.clockReads.get() >= readsAfterPublish + RUNS } shouldBe true
        verify(exactly = 1) { f.storage.getOAuthToken() }
        verify(exactly = 0) { f.service.refreshToken(any()) }

        f.advance(AFTER_EXPIRY_S)

        sent.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
        verify(exactly = 1) { f.service.refreshToken("refresh-stored") }
        f.provider.getToken() shouldBe "new"
      } finally {
        scheduler.stopAndAwait()
      }
    }

    it("R3: a second call registers no second periodic task") {
      val f = Fixture()
      val scheduler = mockk<ScheduledExecutorService>()
      every { scheduler.isShutdown } returns false
      every { scheduler.scheduleWithFixedDelay(any(), any(), any(), any()) } returns mockk()
      f.provider.scheduler = scheduler

      f.provider.startTokenRefreshTimer()
      f.provider.startTokenRefreshTimer()

      verify(exactly = 1) {
        scheduler.scheduleWithFixedDelay(any(), 0, SCHEDULE_PERIOD_MS, TimeUnit.MILLISECONDS)
      }
    }

    it("R4: a call after stopTokenRefreshTimer neither throws nor schedules anything") {
      val f = Fixture()
      val scheduler = Executors.newSingleThreadScheduledExecutor()
      f.provider.scheduler = scheduler
      try {
        every { f.storage.getOAuthToken() } returns expired("stored")

        f.provider.stopTokenRefreshTimer()
        shouldNotThrowAny { f.provider.startTokenRefreshTimer() }

        scheduler.isShutdown shouldBe true
        verify(exactly = 0) { f.storage.getOAuthToken() }
      } finally {
        scheduler.stopAndAwait()
      }
    }

    it("R5: with a personal access token selected, runs neither read secure storage nor refresh") {
      val f = Fixture()
      f.authType.set(TokenProviderType.PAT.name)
      val scheduler = RunCountingScheduler()
      f.provider.scheduler = scheduler
      try {
        every { f.storage.getOAuthToken() } returns expired("stored")
        every { f.service.refreshToken(any()) } returns RefreshOutcome.Refreshed(valid("new"))

        f.provider.startTokenRefreshTimer()

        awaitUntil { scheduler.runs.get() >= RUNS } shouldBe true
        verify(exactly = 0) { f.storage.getOAuthToken() }
        verify(exactly = 0) { f.service.refreshToken(any()) }
      } finally {
        scheduler.stopAndAwait()
      }
    }

    it("R6: logs the scheduled refresh only on the run that refreshed") {
      val logged = captureLog()
      val f = Fixture()
      val scheduler = Executors.newSingleThreadScheduledExecutor()
      f.provider.scheduler = scheduler
      try {
        every { f.storage.getOAuthToken() } returns valid("stored")
        every { f.service.refreshToken(any()) } returns RefreshOutcome.Refreshed(validAt("new", AFTER_EXPIRY_S))

        f.provider.startTokenRefreshTimer()
        f.provider.hasToken() shouldBe true

        awaitUntil { f.clockReads.get() >= RUNS } shouldBe true
        logged.count { it == SCHEDULED_REFRESH_LOG } shouldBe 0

        f.advance(AFTER_EXPIRY_S)
        awaitUntil { logged.contains(SCHEDULED_REFRESH_LOG) } shouldBe true
        // Later runs see the refreshed, valid token and stay silent.
        val readsAfterRefresh = f.clockReads.get()
        awaitUntil { f.clockReads.get() >= readsAfterRefresh + RUNS } shouldBe true
        logged.count { it == SCHEDULED_REFRESH_LOG } shouldBe 1
      } finally {
        scheduler.stopAndAwait()
      }
    }

    it("R7: the check period lets a retry after a failed refresh start before the real expiry") {
      // A failed refresh takes at most 40 s (GitLabOAuthService: 10 s connect + 30 s read timeout).
      val failedAttempt = Duration.ofSeconds(40)
      val buffer = Duration.ofSeconds(TOKEN_EXPIRATION_BUFFER_SECONDS.toLong())

      (OAuthTokenProvider.REFRESH_CHECK_PERIOD <= OAuthTokenProvider.RETRY_BACKOFF) shouldBe true
      (OAuthTokenProvider.REFRESH_CHECK_PERIOD.multipliedBy(2).plus(failedAttempt) < buffer) shouldBe true
    }

    it("R8: the default scheduler runs on a named daemon thread, so it never keeps the JVM alive") {
      val f = Fixture()
      try {
        val thread = f.provider.scheduler.submit<Thread> { Thread.currentThread() }
          .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        thread.isDaemon shouldBe true
        thread.name shouldBe "gitlab-oauth-refresh"
      } finally {
        f.provider.stopTokenRefreshTimer()
      }
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

        f.provider.startTokenRefreshTimer()

        runs.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      } finally {
        scheduler.stopAndAwait()
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

  describe("hasToken (design §7.3, §11)") {
    it("T12c: returns without waiting while another thread holds the refresh lock") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))
      val inRefresh = CountDownLatch(1)
      val releaseRefresh = CountDownLatch(1)
      every { f.service.refreshToken(any()) } answers {
        inRefresh.countDown()
        releaseRefresh.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        RefreshOutcome.Refreshed(valid("new"))
      }

      val refresher = Thread { f.provider.getToken() }.apply { start() }
      try {
        inRefresh.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
        val result = AtomicReference<Boolean?>()
        val checker = Thread { result.set(f.provider.hasToken()) }.apply { start() }
        checker.join(JOIN_TIMEOUT_MS)
        checker.isAlive shouldBe false
        result.get() shouldBe true
      } finally {
        releaseRefresh.countDown()
        refresher.join(JOIN_TIMEOUT_MS)
      }
      verify(exactly = 1) { f.service.refreshToken(any()) }
    }

    it("T16: an expired token counts as present and is not refreshed") {
      val f = Fixture()
      f.provider.updateToken(expired("old"))

      f.provider.hasToken() shouldBe true

      verify(exactly = 0) { f.service.refreshToken(any()) }
    }

    it("T16: loads an expired cached token from secure storage without refreshing it") {
      val f = Fixture()
      every { f.storage.getOAuthToken() } returns expired("cached")

      f.provider.hasToken() shouldBe true

      verify(exactly = 1) { f.storage.getOAuthToken() }
      verify(exactly = 0) { f.service.refreshToken(any()) }
    }

    it("T16: is false when nothing is loaded and secure storage is empty") {
      val f = Fixture()
      every { f.storage.getOAuthToken() } returns null

      f.provider.hasToken() shouldBe false

      verify(exactly = 0) { f.service.refreshToken(any()) }
    }

    it("is false for a token with an empty access token") {
      val f = Fixture()
      f.provider.updateToken(valid(""))

      f.provider.hasToken() shouldBe false
    }
  }
})

private const val WORKERS = 10
private const val T11_WORKERS = 6
private const val JOIN_TIMEOUT_MS = 1_000L
private const val AWAIT_TIMEOUT_MS = 5_000L
private const val SCHEDULE_PERIOD_MS = 50L
private const val POLL_MS = 5L
private const val RUNS = 3
private const val AFTER_EXPIRY_S = 3_600L
private const val SCHEDULED_REFRESH_LOG = "Token refreshed by scheduled task."
private const val TRANSIENT_MESSAGE = "Could not refresh the GitLab OAuth token. Retrying automatically."
private const val REAUTH_MESSAGE = "Failed to refresh the OAuth token. Please re-authenticate."
private val T0: Instant = Instant.ofEpochSecond(1_000_000_000L)

/** Expired at [T0]: created one lifetime ago (the provider also subtracts its 120 s buffer). */
private fun expired(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, T0.epochSecond - 3600)

private fun valid(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, T0.epochSecond)

/** Created [seconds] after [T0], so it is valid once the fixture clock has advanced that far. */
private fun validAt(name: String, seconds: Long) =
  GitLabAuthorizationToken(name, "refresh-$name", 3600, T0.epochSecond + seconds)

/** Installs a log that records every message handed to it, from any thread. */
private fun captureLog(): List<String> {
  val recorded = CopyOnWriteArrayList<String>()
  val log = mockk<ILog>()
  every { log.info(any<String>()) } answers { recorded += firstArg<String>() }
  every { log.warn(any<String>()) } answers { recorded += firstArg<String>() }
  every { log.error(any<String>(), any()) } answers { recorded += firstArg<String>() }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

/**
 * A provider wired to fakes: a stateful authentication type, an injected clock and captured notifications.
 * [clockReads] counts clock reads, so a test can wait for scheduled runs that see a published token.
 */
private class Fixture {
  val authType = AtomicReference(TokenProviderType.OAUTH.name)
  val now = AtomicReference(T0)
  val clockReads = AtomicInteger()
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
    clock = {
      clockReads.incrementAndGet()
      now.get()
    },
    notify = { message ->
      notifications.add(message)
      onNotify(message)
    },
    refreshCheckPeriod = Duration.ofMillis(SCHEDULE_PERIOD_MS),
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

/**
 * A single-threaded scheduler that counts finished task runs. Lets a test wait for runs whose body
 * returns before touching any fake (no token published yet), where no other read counter advances.
 */
private class RunCountingScheduler : ScheduledThreadPoolExecutor(1) {
  val runs = AtomicInteger()

  override fun afterExecute(r: Runnable?, t: Throwable?) {
    super.afterExecute(r, t)
    runs.incrementAndGet()
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

/**
 * Stops the executor and waits for an in-flight run, so no run touches the mocks after the test's
 * `clearAllMocks()`.
 */
private fun ScheduledExecutorService.stopAndAwait() {
  shutdownNow()
  awaitTermination(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
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
