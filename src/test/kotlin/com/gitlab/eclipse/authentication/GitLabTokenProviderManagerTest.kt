package com.gitlab.eclipse.authentication

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertEquals

class GitLabTokenProviderManagerTest : DescribeSpec({
  lateinit var tokenProviderManager: GitLabTokenProviderManager
  val oAuthTokenProvider = mockk<OAuthTokenProvider>()
  val patTokenProvider = mockk<PatTokenProvider>()

  beforeSpec {
    startKoin {
      modules(
        module {
          single<OAuthTokenProvider> { oAuthTokenProvider }
          single<PatTokenProvider> { patTokenProvider }
        }
      )
    }
  }

  beforeTest {
    tokenProviderManager = GitLabTokenProviderManager()
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("GitLabTokenProviderManager") {
    describe("getToken") {
      it("returns OAuth token when available") {
        every { oAuthTokenProvider.getToken() } returns "oauth_token"
        every { patTokenProvider.getToken() } returns "pat_token"

        val result = tokenProviderManager.getToken()

        assertEquals(result, "oauth_token")
      }

      it("returns PAT when OAuth is not available") {
        every { oAuthTokenProvider.getToken() } returns ""
        every { patTokenProvider.getToken() } returns "pat_token"

        val result = tokenProviderManager.getToken()

        assertEquals(result, "pat_token")
      }

      it("returns PAT when explicitly requested") {
        every { oAuthTokenProvider.getToken() } returns "oauth_token"
        every { patTokenProvider.getToken() } returns "pat_token"

        val result = tokenProviderManager.getToken(TokenProviderType.PAT)

        assertEquals(result, "pat_token")
      }

      it("returns empty string when no tokens are available") {
        every { oAuthTokenProvider.getToken() } returns ""
        every { patTokenProvider.getToken() } returns ""

        val result = tokenProviderManager.getToken()

        assertEquals(result, "")
      }
    }
  }
})
