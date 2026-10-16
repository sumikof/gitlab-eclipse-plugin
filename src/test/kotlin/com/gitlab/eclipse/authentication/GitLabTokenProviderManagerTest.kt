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
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
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
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
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
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
        every { patTokenProvider.hasToken() } returns false
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken() shouldBe false

        verifyNoTokenRead()
      }
    }

    // R10: the PAT read goes to the same secure storage, so it would prompt again or block on its lock.
    describe("no PAT fallback while the OAuth first load is pending or failed (F1)") {
      fun selectOAuthWithNoToken(pendingOrFailed: Boolean) {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"
        every { oAuthTokenProvider.getToken() } returns ""
        every { oAuthTokenProvider.hasToken() } returns false
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns pendingOrFailed
        every { patTokenProvider.getToken() } returns "pat_token"
        every { patTokenProvider.hasToken() } returns true
      }

      fun verifyNoPatCall() {
        verify(exactly = 0) { patTokenProvider.getToken() }
        verify(exactly = 0) { patTokenProvider.hasToken() }
      }

      it("F1a: a failed OAuth load answers no token without calling PAT") {
        selectOAuthWithNoToken(pendingOrFailed = true)

        tokenProviderManager.getToken() shouldBe ""
        tokenProviderManager.hasToken() shouldBe false

        verifyNoPatCall()
      }

      it("F1b: a UI-thread hasToken during an in-flight OAuth load answers false without calling PAT") {
        selectOAuthWithNoToken(pendingOrFailed = true)

        tokenProviderManager.hasToken() shouldBe false

        verifyOrder {
          oAuthTokenProvider.hasToken()
          oAuthTokenProvider.isLoadPendingOrFailed()
        }
        verifyNoPatCall()
      }

      it("F1c: an OAuth load that ended empty (switched to PAT) still falls through to PAT") {
        selectOAuthWithNoToken(pendingOrFailed = false)

        tokenProviderManager.getToken() shouldBe "pat_token"
        tokenProviderManager.hasToken() shouldBe true
      }

      it("F1d: with PAT selected the order is unchanged and the OAuth load state is not consulted") {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"
        every { patTokenProvider.getToken() } returns ""
        every { patTokenProvider.hasToken() } returns false
        every { oAuthTokenProvider.getToken() } returns "oauth_token"
        every { oAuthTokenProvider.hasToken() } returns true

        tokenProviderManager.getToken() shouldBe "oauth_token"
        tokenProviderManager.hasToken() shouldBe true

        verifyOrder {
          patTokenProvider.getToken()
          oAuthTokenProvider.getToken()
        }
        verifyOrder {
          patTokenProvider.hasToken()
          oAuthTokenProvider.hasToken()
        }
        verify(exactly = 0) { oAuthTokenProvider.isLoadPendingOrFailed() }
      }
    }
  }
})
