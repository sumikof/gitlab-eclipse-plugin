package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
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

    describe("hasToken") {
      // Never goes through getToken(): that may refresh the OAuth token over the network (design §7.3).
      fun verifyNoTokenRead() {
        verify(exactly = 0) { oAuthTokenProvider.getToken() }
        verify(exactly = 0) { patTokenProvider.getToken() }
      }

      it("is true when OAuth is selected and has a token") {
        every { oAuthTokenProvider.hasToken() } returns true
        every { patTokenProvider.hasToken() } returns false
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken() shouldBe true

        verify(exactly = 0) { patTokenProvider.hasToken() }
        verifyNoTokenRead()
      }

      it("falls back to PAT when OAuth is selected but has no token") {
        every { oAuthTokenProvider.hasToken() } returns false
        every { patTokenProvider.hasToken() } returns true
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken() shouldBe true

        verifyNoTokenRead()
      }

      it("checks the selected PAT provider first") {
        every { oAuthTokenProvider.hasToken() } returns true
        every { patTokenProvider.hasToken() } returns true
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        tokenProviderManager.hasToken() shouldBe true

        verify(exactly = 0) { oAuthTokenProvider.hasToken() }
        verifyNoTokenRead()
      }

      it("is false when no provider has a token") {
        every { oAuthTokenProvider.hasToken() } returns false
        every { patTokenProvider.hasToken() } returns false
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken() shouldBe false

        verifyNoTokenRead()
      }
    }
  }
})
