package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class GitLabApiClientCaptureConnectionTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val client = GitLabApiClient(http, tokens, prefs)

  fun sha256HexPrefix16(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(16)

  beforeEach {
    clearMocks(http, tokens, prefs)
  }

  describe("captureConnection") {
    it("returns the current url and token with a SHA-256-prefix fingerprint and a stable configGeneration") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com/"
      every { tokens.getToken() } returns "tok-123"

      val snapshot = client.captureConnection()

      snapshot.instanceUrl shouldBe "https://gitlab.example.com/"
      snapshot.token shouldBe "tok-123"
      snapshot.authFingerprint shouldBe sha256HexPrefix16("tok-123")
      snapshot.configGeneration shouldBe client.captureConnection().configGeneration
    }

    it("produces identical fingerprints for the same token across captures") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns "same-token"

      client.captureConnection().authFingerprint shouldBe client.captureConnection().authFingerprint
    }

    it("produces a different fingerprint for a different token") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returnsMany listOf("token-a", "token-a", "token-b", "token-b")

      val first = client.captureConnection()
      val second = client.captureConnection()

      first.authFingerprint shouldNotBe second.authFingerprint
      first.configGeneration shouldNotBe second.configGeneration
    }

    it("returns an empty fingerprint for a blank token") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns ""

      client.captureConnection().authFingerprint shouldBe ""
    }

    it("retries a torn read and returns a self-consistent snapshot from the settled generation") {
      // Generation A -> generation B mid-capture: the first (url, token) pair disagrees with the
      // second, then values settle on B. The snapshot must be url-B + token-B, never a mix.
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returnsMany
        listOf("https://a.example.com", "https://b.example.com", "https://b.example.com", "https://b.example.com")
      every { tokens.getToken() } returnsMany listOf("token-a", "token-b", "token-b", "token-b")

      val snapshot = client.captureConnection()

      snapshot.instanceUrl shouldBe "https://b.example.com"
      snapshot.token shouldBe "token-b"
      snapshot.authFingerprint shouldBe sha256HexPrefix16("token-b")
    }

    it("never returns a mixed pair when only the token changes mid-capture") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returnsMany listOf("old-token", "new-token", "new-token", "new-token")

      val snapshot = client.captureConnection()

      snapshot.token shouldBe "new-token"
      snapshot.authFingerprint shouldBe sha256HexPrefix16("new-token")
    }

    it("throws UnstableConnectionException when the settings keep changing every read") {
      var call = 0
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } answers {
        call++
        "https://unstable-$call.example.com"
      }
      every { tokens.getToken() } returns "tok"

      shouldThrow<UnstableConnectionException> { client.captureConnection() }
    }
  }
})
