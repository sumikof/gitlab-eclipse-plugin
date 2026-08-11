package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore

/**
 * Credential-access contract of [pinnedConnectionFor] through a REAL [GitLabApiClient] (issue #49).
 *
 * [WriteActionTest] mocks `captureConnectionIf` wholesale, so it can only pin the classification of
 * the result — whether the credential was read is invisible there. These tests drive the actual
 * seqlock capture with a mocked token manager / preference store so both halves of the scope are
 * pinned: a url mismatch must never reach the token manager, and a same-url account change must
 * still reach it (the fingerprint is derived from the token, so that case is deliberately NOT
 * solved by the predicate — see the design's "account side is out of scope").
 */
class PinnedConnectionCredentialAccessTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  var genFn: () -> Long = { 2L }
  val client = GitLabApiClient(http, tokens, prefs) { genFn() }

  val nodeUrl = "https://gitlab.example.com/"

  beforeEach {
    clearMocks(http, tokens, prefs)
    genFn = { 2L }
  }

  describe("pinnedConnectionFor over a real GitLabApiClient") {
    it("still reads the credential once, and rejects, when the url matches but the account changed") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns "current-account-token"

      val pinned = pinnedConnectionFor(client, nodeUrl, "fp-of-a-different-account")

      pinned.shouldBeNull()
      verify(exactly = 1) { tokens.getToken() }
    }

    it("never reads the credential when the configured instance url no longer matches the node") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://other.example.com"
      every { tokens.getToken() } returns "current-account-token"

      val pinned = pinnedConnectionFor(client, nodeUrl, "fp-node")

      pinned.shouldBeNull()
      verify(exactly = 0) { tokens.getToken() }
    }
  }
})
