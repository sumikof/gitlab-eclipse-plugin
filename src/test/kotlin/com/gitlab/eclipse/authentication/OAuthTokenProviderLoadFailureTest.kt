package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.google.gson.JsonSyntaxException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainOnly
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Spec for the first load from secure storage when it cannot be read (e.g. the master-password prompt was
 * cancelled): OAuth stays selected, one notification, no further reads until a sign-in. Empty or corrupt
 * storage keeps switching to a personal access token. Split from [OAuthTokenProviderTest] for its size.
 */
class OAuthTokenProviderLoadFailureTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  afterEach { clearAllMocks() }

  describe("first load failure (C1)") {
    it("L1: a secure storage failure keeps OAuth selected and reports no token") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } throws storageFailure()

      f.provider.hasToken() shouldBe false
      f.provider.getToken() shouldBe ""

      verify(exactly = 0) {
        f.prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, TokenProviderType.PAT.name)
      }
      f.authType.get() shouldBe TokenProviderType.OAUTH.name
    }

    it("L2: after a failure, later calls do not read secure storage again") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } throws storageFailure()

      repeat(CALLS) {
        f.provider.hasToken() shouldBe false
        f.provider.getToken() shouldBe ""
      }

      verify(exactly = 1) { f.storage.getOAuthToken() }
    }

    it("L3: concurrent callers that all fail to load notify exactly once") {
      val f = LoadFailureFixture()
      val entered = AtomicInteger()
      every { f.storage.getOAuthToken() } answers {
        entered.incrementAndGet()
        // Every caller is inside the read before any of them fails, so all of them reach the catch.
        awaitUntil { entered.get() >= WORKERS }
        throw storageFailure()
      }

      val results = runConcurrently(WORKERS) { f.provider.hasToken() }

      results.size shouldBe WORKERS
      results shouldContainOnly listOf(false)
      entered.get() shouldBe WORKERS
      f.notifications shouldBe listOf(LOAD_FAILURE_MESSAGE)

      f.provider.hasToken() shouldBe false
      f.notifications shouldBe listOf(LOAD_FAILURE_MESSAGE)
    }

    it("L4: a sign-in after the failure publishes the new token") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } throws storageFailure()
      f.provider.hasToken() shouldBe false

      f.provider.updateToken(validToken("signed-in"))

      f.provider.getToken() shouldBe "signed-in"
      f.provider.hasToken() shouldBe true
      verify(exactly = 1) { f.storage.getOAuthToken() }
    }

    it("L5: a corrupt stored token still switches to a personal access token") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } throws JsonSyntaxException("corrupt")

      f.provider.hasToken() shouldBe false

      f.authType.get() shouldBe TokenProviderType.PAT.name
      f.notifications shouldBe emptyList()
    }

    it("L6: empty secure storage still switches to a personal access token") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } returns null

      f.provider.hasToken() shouldBe false

      f.authType.get() shouldBe TokenProviderType.PAT.name
      f.notifications shouldBe emptyList()
    }

    it("L7: a failure after stopTokenRefreshTimer does not notify") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } throws storageFailure()

      f.provider.stopTokenRefreshTimer()
      f.provider.hasToken() shouldBe false

      f.notifications shouldBe emptyList()
      f.authType.get() shouldBe TokenProviderType.OAUTH.name
    }

    it("L8: after a failure, a scheduled run does not read secure storage") {
      val f = LoadFailureFixture()
      every { f.storage.getOAuthToken() } throws storageFailure()
      f.provider.hasToken() shouldBe false

      f.provider.runScheduledRefresh()

      verify(exactly = 1) { f.storage.getOAuthToken() }
      verify(exactly = 0) { f.service.refreshToken(any()) }
    }
  }
})

private const val WORKERS = 10
private const val CALLS = 3
private const val AWAIT_TIMEOUT_MS = 5_000L
private const val POLL_MS = 5L
private const val LOAD_FAILURE_MESSAGE =
  "Could not read the GitLab OAuth token from secure storage. Restart Eclipse or sign in again."
private val NOW: Instant = Instant.ofEpochSecond(1_000_000_000L)

/** What Equinox throws when the master-password prompt is cancelled. */
private fun storageFailure() = StorageException(StorageException.NO_PASSWORD, "cancelled")

private fun validToken(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, NOW.epochSecond)

/** A provider wired to fakes: a stateful authentication type (OAuth at first) and captured notifications. */
private class LoadFailureFixture {
  val authType = AtomicReference(TokenProviderType.OAUTH.name)
  val notifications = CopyOnWriteArrayList<String>()
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
  )

  init {
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } answers { authType.get() }
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } answers {
      authType.set(secondArg())
    }
    every { lsConfig.sendConfiguration() } just Runs
    every { storage.setOAuthToken(any()) } just Runs
  }
}

/** Runs [task] on [count] threads released together; returns their results once all have finished. */
private fun <T> runConcurrently(count: Int, task: () -> T): List<T?> {
  val start = CountDownLatch(1)
  val results = CopyOnWriteArrayList<T?>()
  val threads = List(count) {
    Thread {
      start.await()
      results.add(task())
    }
  }
  threads.forEach { it.start() }
  start.countDown()
  threads.forEach { it.join(AWAIT_TIMEOUT_MS) }
  check(threads.none { it.isAlive }) { "workers did not finish" }
  return results.toList()
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
