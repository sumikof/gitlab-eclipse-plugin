package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.storage.FakeSecurePreferences
import com.gitlab.eclipse.preferences.storage.GitLabInstanceKey
import com.gitlab.eclipse.preferences.storage.InstanceSecretStore
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference

/**
 * The OAuth token is issued by gitlab.com (`GitLabOAuthService`), so it is stored under the gitlab.com instance
 * and handed out for that instance only, whatever instance is configured (#115, design §4.4).
 */
class OAuthTokenProviderInstanceScopeTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var root: FakeSecurePreferences
  lateinit var store: InstanceSecretStore
  lateinit var storage: OAuthSecretStorage
  lateinit var provider: OAuthTokenProvider
  val authType = AtomicReference(TokenProviderType.OAUTH.name)

  fun token(access: String) = GitLabAuthorizationToken(access, "refresh-$access", 3600, Instant.now().epochSecond)

  beforeEach {
    root = FakeSecurePreferences()
    store = InstanceSecretStore(root = { root }, configuredInstanceUrl = { "https://gitlab.com" }, notify = {})
    storage = spyk(OAuthSecretStorage { store.storageFor(GitLabOAuthService.ISSUER_KEY) })
    authType.set(TokenProviderType.OAUTH.name)
    val prefs = mockk<ScopedPreferenceStore>()
    every { prefs.getString(PreferenceConstants.AUTHENTICATION_TYPE) } answers { authType.get() }
    every { prefs.setValue(PreferenceConstants.AUTHENTICATION_TYPE, any<String>()) } answers {
      authType.set(secondArg())
    }
    val lsConfig = mockk<GitLabLanguageServerConfigurationService>(relaxed = true)
    provider = OAuthTokenProvider(
      lsConfig,
      prefs,
      storage,
      oAuthService = { mockk() },
      // Headless: the default notification would load SWT's Display.
      notify = { _, _ -> },
      isUiThread = { false },
    )
    // A shut-down scheduler makes the timer registration in updateToken a no-op.
    provider.scheduler = ScheduledThreadPoolExecutor(1).also { it.shutdown() }
  }

  describe("the issuer") {
    it("is gitlab.com") {
      GitLabOAuthService.ISSUER_KEY shouldBe GitLabInstanceKey.GITLAB_COM
      GitLabInstanceKey.of(GitLabOAuthService.ISSUER_URL) shouldBe GitLabOAuthService.ISSUER_KEY
    }
  }

  describe("for a self-managed instance") {
    it("hands out no token and does not read secure storage or switch to PAT") {
      storage.setOAuthToken(token("stored"))

      provider.getToken("https://self.example") shouldBe ""
      provider.hasToken("https://self.example") shouldBe false

      verify(exactly = 0) { storage.getOAuthToken() }
      authType.get() shouldBe TokenProviderType.OAUTH.name
    }

    it("does not hand out the token already cached for gitlab.com") {
      provider.updateToken(token("cached"))

      provider.getToken("https://gitlab.com") shouldBe "cached"
      provider.getToken("https://self.example") shouldBe ""
      provider.getToken("http://gitlab.com") shouldBe ""
      provider.hasToken("https://self.example") shouldBe false
    }
  }

  describe("for gitlab.com") {
    it("loads the stored token, whatever the spelling of the URL") {
      storage.setOAuthToken(token("stored"))

      provider.getToken("https://GitLab.com/") shouldBe "stored"
      provider.hasToken("https://gitlab.com") shouldBe true
    }
  }

  describe("when the legacy token cannot be migrated this session (master password cancelled)") {
    it("keeps OAuth selected instead of switching to PAT") {
      root.node("hosts/gitlab.com").put(OAuthSecretStorage.SECRET_NAME, "legacy-json", true)
      root.failDecrypt = true

      provider.getToken("https://gitlab.com") shouldBe ""

      authType.get() shouldBe TokenProviderType.OAUTH.name
    }
  }

  describe("persistence") {
    it("stores a token received after the stop under the gitlab.com instance only") {
      provider.stopTokenRefreshTimer()

      provider.updateToken(token("after-stop"))

      store.storageFor("https://gitlab.com")!!.readSecret(OAuthSecretStorage.SECRET_NAME).shouldNotBeNull()
      store.storageFor("https://self.example")!!.readSecret(OAuthSecretStorage.SECRET_NAME).shouldBeNull()
      OAuthSecretStorage { store.storageFor(GitLabOAuthService.ISSUER_KEY) }
        .getOAuthToken()?.accessToken shouldBe "after-stop"
    }
  }
})
