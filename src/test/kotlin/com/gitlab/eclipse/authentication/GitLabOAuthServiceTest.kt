package com.gitlab.eclipse.authentication

import com.github.scribejava.core.model.OAuth2AccessToken
import com.github.scribejava.core.oauth.OAuth20Service
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.*
import java.awt.Desktop
import java.net.URI

@Suppress("IgnoredReturnValue")
class GitLabOAuthServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic(Desktop::class)
  }

  afterSpec {
    unmockkAll()
  }
  var gitLabOAuthService = spyk(GitLabOAuthService())

  describe("startOAuthFlow") {
    it("should not attempt to open browser when desktop is not supported") {
      gitLabOAuthService = spyk(GitLabOAuthService())
      val desktopMock = mockk<Desktop>()
      every { Desktop.isDesktopSupported() } returns false
      every { Desktop.getDesktop() } returns desktopMock

      gitLabOAuthService.startOAuthFlow()

      verify(exactly = 0) {
        Desktop.getDesktop()
      }
    }

    it("should open the browser with the correct authorization URL") {
      val desktopMock = mockk<Desktop>()
      every { Desktop.isDesktopSupported() } returns true
      every { Desktop.getDesktop() } returns desktopMock
      every { desktopMock.browse(any()) } just Runs

      val serverMock = mockk<OAuthCallbackServer>()
      every { serverMock.start() } just Runs
      every { gitLabOAuthService.createServer(any()) } returns serverMock

      gitLabOAuthService.startOAuthFlow()

      verify {
        desktopMock.browse(
          match<URI> { uri ->
            uri.toString().startsWith("https://gitlab.com/oauth/authorize") &&
              uri.toString().contains("client_id=") &&
              uri.toString()
                .contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A63343%2Fapi%2Foauth%2Fgitlab%2Fauthorization") &&
              uri.toString().contains("code_challenge=") &&
              uri.toString().contains("code_challenge_method=S256")
          }
        )
        serverMock.start()
      }
    }
  }

  describe("refreshToken") {
    val mockOAuthService = mockk<OAuth20Service>()
    val mockGson = mockk<Gson>()

    beforeTest {
      val oauthServiceField = GitLabOAuthService::class.java.getDeclaredField("oauthService")
      oauthServiceField.isAccessible = true
      oauthServiceField.set(gitLabOAuthService, mockOAuthService)

      val gsonField = GitLabOAuthService::class.java.getDeclaredField("gson")
      gsonField.isAccessible = true
      gsonField.set(gitLabOAuthService, mockGson)
    }

    it("should refresh the token and return a new GitLabAuthorizationToken") {
      val currentToken = "old_refresh_token"
      val newAccessToken = OAuth2AccessToken(
        "new_access_token",
        "new_token_type",
        3600,
        "new_refresh_token",
        "new_scope",
        "{\"access_token\":\"new_access_token\",\"refresh_token\":\"new_refresh_token\",\"expires_in\":3600,\"created_at\":1234567890}"
      )
      val expectedGitLabToken = GitLabAuthorizationToken("new_access_token", "new_refresh_token", 3600, 1234567890L)

      every { mockOAuthService.refreshAccessToken(currentToken, "api") } returns newAccessToken
      every {
        mockGson.fromJson(
          newAccessToken.rawResponse,
          GitLabAuthorizationToken::class.java
        )
      } returns expectedGitLabToken

      val result = gitLabOAuthService.refreshToken(currentToken)

      result.shouldBeTypeOf<GitLabAuthorizationToken>()
      result shouldBe expectedGitLabToken
      verify(exactly = 1) { mockOAuthService.refreshAccessToken(currentToken, "api") }
      verify(exactly = 1) { mockGson.fromJson(newAccessToken.rawResponse, GitLabAuthorizationToken::class.java) }
    }

    it("should return null when token refresh fails") {
      val currentToken = "invalid_refresh_token"

      every { mockOAuthService.refreshAccessToken(currentToken, "api") } throws Exception("Token refresh failed")

      val result = gitLabOAuthService.refreshToken(currentToken)

      result shouldBe null
      verify(exactly = 1) { mockOAuthService.refreshAccessToken(currentToken, "api") }
    }

    it("should return null when Gson parsing fails") {
      val currentToken = "valid_refresh_token"
      val newAccessToken = OAuth2AccessToken(
        "new_access_token",
        "new_token_type",
        3600,
        "new_refresh_token",
        "new_scope",
        "invalid_json"
      )

      every { mockOAuthService.refreshAccessToken(currentToken, "api") } returns newAccessToken
      every {
        mockGson.fromJson(
          newAccessToken.rawResponse,
          GitLabAuthorizationToken::class.java
        )
      } throws Exception("JSON parsing failed")

      val result = gitLabOAuthService.refreshToken(currentToken)

      result shouldBe null
      verify(exactly = 1) { mockOAuthService.refreshAccessToken(currentToken, "api") }
      verify(exactly = 1) { mockGson.fromJson(newAccessToken.rawResponse, GitLabAuthorizationToken::class.java) }
    }
  }
})
