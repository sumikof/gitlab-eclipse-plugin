package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.google.gson.JsonSyntaxException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spec for the ordering of the first load against a sign-in: an empty or corrupt read that a sign-in
 * superseded while it was pending must not switch to a personal access token. Split from
 * [OAuthTokenProviderLoadFailureTest] for its size.
 */
class OAuthTokenProviderLoadOrderingTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  afterEach { clearAllMocks() }

  describe("switch to PAT ordered against a sign-in (B)") {
    it("B1: an empty read superseded by a sign-in keeps OAuth selected") {
      val f = LoadOrderingFixture()
      f.holdReadThen { null }

      f.signInDuringRead()

      f.authType.get() shouldBe TokenProviderType.OAUTH.name
      f.provider.getToken() shouldBe "signed-in"
      f.provider.hasToken() shouldBe true
    }

    it("B2: a corrupt read superseded by a sign-in keeps OAuth selected") {
      val f = LoadOrderingFixture()
      f.holdReadThen { throw JsonSyntaxException("corrupt") }

      f.signInDuringRead()

      f.authType.get() shouldBe TokenProviderType.OAUTH.name
      f.provider.getToken() shouldBe "signed-in"
      f.provider.hasToken() shouldBe true
    }

    it("B3: an empty read without a sign-in still switches to a personal access token") {
      val f = LoadOrderingFixture()
      f.holdReadThen { null }

      val loader = Thread { f.provider.hasToken() }.apply { start() }
      f.reading.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
      f.release.countDown()
      loader.join(AWAIT_TIMEOUT_MS)
      loader.isAlive shouldBe false

      f.authType.get() shouldBe TokenProviderType.PAT.name
      f.provider.getToken() shouldBe ""
    }
  }
})

private const val AWAIT_TIMEOUT_MS = 5_000L
private val NOW: Instant = Instant.ofEpochSecond(1_000_000_000L)

private fun validToken(name: String) = GitLabAuthorizationToken(name, "refresh-$name", 3600, NOW.epochSecond)

/** A provider wired to fakes: a stateful authentication type (OAuth at first) and a read held on a latch. */
private class LoadOrderingFixture {
  val authType = AtomicReference(TokenProviderType.OAUTH.name)
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
    notify = { _, _ -> },
    // Headless: the default would load SWT's Display, which has no native library here.
    isUiThread = { false },
  )
  val reading = CountDownLatch(1)
  val release = CountDownLatch(1)

  init {
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } answers { authType.get() }
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } answers {
      authType.set(secondArg())
    }
    every { lsConfig.sendConfigurationIf(any()) } just Runs
    every { storage.setOAuthToken(any()) } just Runs
  }

  /** The storage read signals [reading], waits for [release], then ends with [outcome]. */
  fun holdReadThen(outcome: () -> GitLabAuthorizationToken?) {
    every { storage.getOAuthToken() } answers {
      reading.countDown()
      release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      outcome()
    }
  }

  /** The read starts on a background thread, a sign-in completes while it is pending, then the read ends. */
  fun signInDuringRead() {
    val loader = Thread { provider.hasToken() }.apply { start() }
    reading.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) shouldBe true
    provider.updateToken(validToken("signed-in"))
    release.countDown()
    loader.join(AWAIT_TIMEOUT_MS)
    loader.isAlive shouldBe false
  }
}
