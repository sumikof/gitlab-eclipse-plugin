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

private const val URL = "https://gitlab.com"
private const val SELF_MANAGED = "https://self.example"

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
    every { oAuthTokenProvider.serves(any()) } answers { firstArg<String>() == URL }
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("GitLabTokenProviderManager") {
    describe("getToken") {
      it("returns OAuth token when available") {
        every { oAuthTokenProvider.getToken(URL) } returns "oauth_token"
        every { patTokenProvider.getToken(URL) } returns "pat_token"
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        val result = tokenProviderManager.getToken(URL)

        assertEquals(result, "pat_token")
      }

      it("returns PAT when OAuth is not available") {
        every { oAuthTokenProvider.getToken(URL) } returns ""
        every { patTokenProvider.getToken(URL) } returns "pat_token"
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        val result = tokenProviderManager.getToken(URL)

        assertEquals(result, "pat_token")
      }

      it("returns PAT when explicitly requested") {
        every { oAuthTokenProvider.getToken(URL) } returns "oauth_token"
        every { patTokenProvider.getToken(URL) } returns "pat_token"
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        val result = tokenProviderManager.getToken(URL)

        assertEquals(result, "pat_token")
      }

      it("returns empty string when no tokens are available") {
        every { oAuthTokenProvider.getToken(URL) } returns ""
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
        every { patTokenProvider.getToken(URL) } returns ""
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        val result = tokenProviderManager.getToken(URL)

        assertEquals(result, "")
      }
    }

    describe("hasToken") {
      // Never goes through getToken(): that may refresh the OAuth token over the network (design §7.3).
      fun verifyNoTokenRead() {
        verify(exactly = 0) { oAuthTokenProvider.getToken(URL) }
        verify(exactly = 0) { patTokenProvider.getToken(URL) }
      }

      it("is true when OAuth is selected and has a token") {
        every { oAuthTokenProvider.hasToken(URL) } returns true
        every { patTokenProvider.hasToken(URL) } returns false
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken(URL) shouldBe true

        verify(exactly = 0) { patTokenProvider.hasToken(URL) }
        verifyNoTokenRead()
      }

      it("falls back to PAT when OAuth is selected but has no token") {
        every { oAuthTokenProvider.hasToken(URL) } returns false
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
        every { patTokenProvider.hasToken(URL) } returns true
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken(URL) shouldBe true

        verifyNoTokenRead()
      }

      it("checks the selected PAT provider first") {
        every { oAuthTokenProvider.hasToken(URL) } returns true
        every { patTokenProvider.hasToken(URL) } returns true
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"

        tokenProviderManager.hasToken(URL) shouldBe true

        verify(exactly = 0) { oAuthTokenProvider.hasToken(URL) }
        verifyNoTokenRead()
      }

      it("is false when no provider has a token") {
        every { oAuthTokenProvider.hasToken(URL) } returns false
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
        every { patTokenProvider.hasToken(URL) } returns false
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"

        tokenProviderManager.hasToken(URL) shouldBe false

        verifyNoTokenRead()
      }
    }

    // R10: the PAT read goes to the same secure storage, so it would prompt again or block on its lock.
    describe("no PAT fallback while the OAuth first load is pending or failed (F1)") {
      fun selectOAuthWithNoToken(pendingOrFailed: Boolean) {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"
        every { oAuthTokenProvider.getToken(URL) } returns ""
        every { oAuthTokenProvider.hasToken(URL) } returns false
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns pendingOrFailed
        every { patTokenProvider.getToken(URL) } returns "pat_token"
        every { patTokenProvider.hasToken(URL) } returns true
      }

      fun verifyNoPatCall() {
        verify(exactly = 0) { patTokenProvider.getToken(URL) }
        verify(exactly = 0) { patTokenProvider.hasToken(URL) }
      }

      it("F1a: a failed OAuth load answers no token without calling PAT") {
        selectOAuthWithNoToken(pendingOrFailed = true)

        tokenProviderManager.getToken(URL) shouldBe ""
        tokenProviderManager.hasToken(URL) shouldBe false

        verifyNoPatCall()
      }

      it("F1b: a UI-thread hasToken during an in-flight OAuth load answers false without calling PAT") {
        selectOAuthWithNoToken(pendingOrFailed = true)

        tokenProviderManager.hasToken(URL) shouldBe false

        verifyOrder {
          oAuthTokenProvider.hasToken(URL)
          oAuthTokenProvider.isLoadPendingOrFailed()
        }
        verifyNoPatCall()
      }

      it("F1c: an OAuth load that ended empty (switched to PAT) still falls through to PAT") {
        selectOAuthWithNoToken(pendingOrFailed = false)

        tokenProviderManager.getToken(URL) shouldBe "pat_token"
        tokenProviderManager.hasToken(URL) shouldBe true
      }

      it("F1d: with PAT selected the order is unchanged and the OAuth load state is not consulted") {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"
        every { patTokenProvider.getToken(URL) } returns ""
        every { patTokenProvider.hasToken(URL) } returns false
        every { oAuthTokenProvider.getToken(URL) } returns "oauth_token"
        every { oAuthTokenProvider.hasToken(URL) } returns true

        tokenProviderManager.getToken(URL) shouldBe "oauth_token"
        tokenProviderManager.hasToken(URL) shouldBe true

        verifyOrder {
          patTokenProvider.getToken(URL)
          oAuthTokenProvider.getToken(URL)
        }
        verifyOrder {
          patTokenProvider.hasToken(URL)
          oAuthTokenProvider.hasToken(URL)
        }
        verify(exactly = 0) { oAuthTokenProvider.isLoadPendingOrFailed() }
      }
    }
    // #115: a token is only ever asked for, and answered for, the instance it will be sent to.
    describe("instance scope") {
      it("asks every provider for the instance it was given") {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"
        every { oAuthTokenProvider.getToken(SELF_MANAGED) } returns ""
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns false
        every { patTokenProvider.getToken(SELF_MANAGED) } returns "self_pat"

        tokenProviderManager.getToken(SELF_MANAGED) shouldBe "self_pat"

        verify(exactly = 0) { patTokenProvider.getToken(neq(SELF_MANAGED)) }
        verify(exactly = 0) { oAuthTokenProvider.getToken(neq(SELF_MANAGED)) }
      }

      it("falls back to the PAT of a self-managed instance even when the OAuth first load failed") {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "OAUTH"
        every { oAuthTokenProvider.getToken(SELF_MANAGED) } returns ""
        every { oAuthTokenProvider.hasToken(SELF_MANAGED) } returns false
        every { oAuthTokenProvider.isLoadPendingOrFailed() } returns true
        every { patTokenProvider.getToken(SELF_MANAGED) } returns "self_pat"
        every { patTokenProvider.hasToken(SELF_MANAGED) } returns true

        tokenProviderManager.getToken(SELF_MANAGED) shouldBe "self_pat"
        tokenProviderManager.hasToken(SELF_MANAGED) shouldBe true
      }

      it("checks the configured instance when no instance is given") {
        every { scopedPreferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) } returns "PAT"
        every { scopedPreferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns SELF_MANAGED
        every { patTokenProvider.hasToken(SELF_MANAGED) } returns true

        tokenProviderManager.hasToken() shouldBe true

        verify(exactly = 0) { patTokenProvider.hasToken(URL) }
      }
    }
  }
})
