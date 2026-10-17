package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Spec for registering the refresh timer from [OAuthTokenProvider.updateToken]: a session whose startup
 * failed before it registered the timer still gets refreshes after a sign-in. Split from
 * [OAuthTokenProviderTest] for its size.
 */
class OAuthTokenProviderTimerOnSignInTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  afterEach { clearAllMocks() }

  describe("timer registration on sign-in (C)") {
    it("C1t: updateToken registers the periodic task when startup did not") {
      val f = TimerFixture()
      try {
        f.provider.updateToken(validToken("signed-in"))

        f.scheduler.registrations.get() shouldBe 1
      } finally {
        f.close()
      }
    }

    it("C2t: after stop, updateToken does not throw and registers nothing") {
      val f = TimerFixture()
      try {
        f.provider.stopTokenRefreshTimer()

        f.provider.updateToken(validToken("signed-in"))

        f.scheduler.registrations.get() shouldBe 0
      } finally {
        f.close()
      }
    }

    it("C3t: updateToken twice, or after the startup registration, registers once") {
      val twice = TimerFixture()
      val afterStartup = TimerFixture()
      try {
        twice.provider.updateToken(validToken("one"))
        twice.provider.updateToken(validToken("two"))
        afterStartup.provider.startTokenRefreshTimer()
        afterStartup.provider.updateToken(validToken("signed-in"))

        twice.scheduler.registrations.get() shouldBe 1
        afterStartup.scheduler.registrations.get() shouldBe 1
      } finally {
        twice.close()
        afterStartup.close()
      }
    }

    it("C4t: stop racing updateToken throws nothing and no run happens after stop returns") {
      repeat(RACE_ROUNDS) {
        val f = TimerFixture()
        try {
          val start = CountDownLatch(1)
          val failure = AtomicReference<Throwable>()
          val signIn = Thread {
            try {
              start.await()
              f.provider.updateToken(validToken("signed-in"))
            } catch (t: Throwable) {
              failure.set(t)
            }
          }.apply { start() }
          val stopper = Thread {
            try {
              start.await()
              f.provider.stopTokenRefreshTimer()
            } catch (t: Throwable) {
              failure.set(t)
            }
          }.apply { start() }
          start.countDown()
          signIn.join(AWAIT_TIMEOUT_MS)
          stopper.join(AWAIT_TIMEOUT_MS)
          signIn.isAlive shouldBe false
          stopper.isAlive shouldBe false
          failure.get() shouldBe null

          f.scheduler.awaitTermination(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
          val runsAtStop = f.scheduler.runs.get()
          settle()
          f.scheduler.runs.get() shouldBe runsAtStop
        } finally {
          f.close()
        }
      }
    }
  }
})

private const val AWAIT_TIMEOUT_MS = 5_000L
private const val SETTLE_MS = 50L
private const val RACE_ROUNDS = 20
private const val PERIOD_MS = 5L
private val NOW: Instant = Instant.ofEpochSecond(1_000_000_000L)

private fun validToken(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, NOW.epochSecond)

/** Counts periodic registrations and finished task runs. */
private class CountingScheduler : ScheduledThreadPoolExecutor(1) {
  val registrations = AtomicInteger()
  val runs = AtomicInteger()

  override fun scheduleWithFixedDelay(
    command: Runnable,
    initialDelay: Long,
    delay: Long,
    unit: TimeUnit,
  ): ScheduledFuture<*> {
    registrations.incrementAndGet()
    return super.scheduleWithFixedDelay(command, initialDelay, delay, unit)
  }

  override fun afterExecute(r: Runnable?, t: Throwable?) {
    super.afterExecute(r, t)
    runs.incrementAndGet()
  }
}

/** A provider wired to fakes with a [CountingScheduler]; no timer is started. */
private class TimerFixture {
  val scheduler = CountingScheduler()
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
    notify = {},
    refreshCheckPeriod = Duration.ofMillis(PERIOD_MS),
    // Headless: the default would load SWT's Display, which has no native library here.
    isUiThread = { false },
  ).also { it.scheduler = scheduler }

  init {
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns TokenProviderType.OAUTH.name
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } just Runs
    every { lsConfig.sendConfiguration() } just Runs
    every { storage.setOAuthToken(any()) } just Runs
  }

  fun close() {
    scheduler.shutdownNow()
    scheduler.awaitTermination(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }
}

/** Lets a stray periodic run, if any, happen; a plain function so the spec body stays free of sleeps. */
private fun settle() = Thread.sleep(SETTLE_MS)
