package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.delay
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class OAuthTokenProviderTest : DescribeSpec({
  val oAuthService = mockk<GitLabOAuthService>()
  val languageServerConfigurationService = mockk<GitLabLanguageServerConfigurationService>()
  val scopedPreferenceStore = mockk<ScopedPreferenceStore>()
  val tokenProvider = OAuthTokenProvider(languageServerConfigurationService, scopedPreferenceStore)

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
      } returns newToken

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

      every { oAuthService.refreshToken(any()) } returns newToken
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
})
