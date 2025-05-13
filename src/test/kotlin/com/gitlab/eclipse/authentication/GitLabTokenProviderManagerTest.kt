package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertEquals

class GitLabTokenProviderManagerTest : DescribeSpec({
  lateinit var tokenProviderManager: GitLabTokenProviderManager
  val oAuthTokenProvider = mockk<OAuthTokenProvider>()
  val patTokenProvider = mockk<PatTokenProvider>()
  val scopedPreferenceStore = mockk<ScopedPreferenceStore>()

  beforeSpec {
    startKoin {
      modules(
        module {
          single<OAuthTokenProvider> { oAuthTokenProvider }
          single<PatTokenProvider> { patTokenProvider }
          single<ScopedPreferenceStore> { scopedPreferenceStore }
        }
      )
    }
  }

  beforeTest {
    tokenProviderManager = GitLabTokenProviderManager(scopedPreferenceStore)
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
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        val result = tokenProviderManager.getToken()

        assertEquals(result, "pat_token")
      }

      it("returns PAT when OAuth is not available") {
        every { oAuthTokenProvider.getToken() } returns ""
        every { patTokenProvider.getToken() } returns "pat_token"
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        val result = tokenProviderManager.getToken()

        assertEquals(result, "pat_token")
      }

      it("returns PAT when explicitly requested") {
        every { oAuthTokenProvider.getToken() } returns "oauth_token"
        every { patTokenProvider.getToken() } returns "pat_token"
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        val result = tokenProviderManager.getToken()

        assertEquals(result, "pat_token")
      }

      it("returns empty string when no tokens are available") {
        every { oAuthTokenProvider.getToken() } returns ""
        every { patTokenProvider.getToken() } returns ""
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        val result = tokenProviderManager.getToken()

        assertEquals(result, "")
      }
    }
  }
})
