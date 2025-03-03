package com.gitlab.eclipse.authentication

import com.github.scribejava.core.model.OAuth2AccessToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class OAuthTokenProviderTest : DescribeSpec({
  describe("getToken") {
    val tokenProvider = OAuthTokenProvider()

    it("should return empty string when no token is set") {
      tokenProvider.getToken() shouldBe ""
    }

    describe("when a token is set") {
      val mockToken = mockk<OAuth2AccessToken>()
      val tokenValue = "test-access-token"

      beforeTest {
        every { mockToken.accessToken } returns tokenValue
        tokenProvider.updateToken(mockToken)
      }

      it("should return the token value") {
        tokenProvider.getToken() shouldBe tokenValue
      }

      describe("when token is updated") {
        val newTokenValue = "new-access-token"
        val newMockToken = mockk<OAuth2AccessToken>()

        beforeTest {
          every { newMockToken.accessToken } returns newTokenValue
          tokenProvider.updateToken(newMockToken)
        }

        it("should return the new token value") {
          tokenProvider.getToken() shouldBe newTokenValue
        }
      }
    }
  }
})
