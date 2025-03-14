package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Instant
import kotlin.test.assertEquals

class OAuthTokenProviderTest : DescribeSpec({
  val tokenProvider = OAuthTokenProvider()
  val oAuthService = mockk<GitLabOAuthService>()

  extensions(LoggingKotestExtension)

  startKoin {
    modules(
      module {
        single<GitLabOAuthService> { oAuthService }
      }
    )
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
})
