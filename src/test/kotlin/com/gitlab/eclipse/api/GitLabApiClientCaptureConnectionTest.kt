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
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class GitLabApiClientCaptureConnectionTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  var genFn: () -> Long = { 0L }
  val client = GitLabApiClient(http, tokens, prefs) { genFn() }

  fun sha256HexPrefix16(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(16)

  beforeEach {
    clearMocks(http, tokens, prefs)
    genFn = { 0L }
  }

  describe("captureConnection") {
    it("returns the current url and token with a SHA-256-prefix fingerprint at a stable even generation") {
      genFn = { 4L }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com/"
      every { tokens.getToken() } returns "tok-123"

      val snapshot = client.captureConnection()

      snapshot.instanceUrl shouldBe "https://gitlab.example.com/"
      snapshot.token shouldBe "tok-123"
      snapshot.authFingerprint shouldBe sha256HexPrefix16("tok-123")
      snapshot.configGeneration shouldBe 4L
    }

    it("produces identical fingerprints for the same token across captures") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns "same-token"

      client.captureConnection().authFingerprint shouldBe client.captureConnection().authFingerprint
    }

    it("produces a different fingerprint for a different token") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returnsMany listOf("token-a", "token-b")

      val first = client.captureConnection()
      val second = client.captureConnection()

      first.authFingerprint shouldNotBe second.authFingerprint
    }

    it("returns an empty fingerprint for a blank token") {
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns ""

      client.captureConnection().authFingerprint shouldBe ""
    }

    it("does not read any value while an update is in progress, and retries until the generation is even") {
      // Generation odd (update in progress) for the first two attempts, then settles at even 2.
      val generations = ArrayDeque(listOf(1L, 1L, 2L, 2L))
      genFn = { generations.removeFirst() }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns "tok"

      val snapshot = client.captureConnection()

      snapshot.instanceUrl shouldBe "https://gitlab.example.com"
      snapshot.token shouldBe "tok"
      snapshot.configGeneration shouldBe 2L
      // The odd attempts must not have touched the stores: exactly one value read each.
      verify(exactly = 1) { tokens.getToken() }
      verify(exactly = 1) { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) }
    }

    it("discards a value pair read across a generation change and returns the settled pair") {
      // Attempt 1: g1=0 (even), the write starts mid-read, so the values are torn and g2=1 -> retry.
      // Attempt 2: g1=g2=2 -> the settled pair is returned.
      val generations = ArrayDeque(listOf(0L, 1L, 2L, 2L))
      genFn = { generations.removeFirst() }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returnsMany
        listOf("https://new.example.com", "https://new.example.com")
      every { tokens.getToken() } returnsMany listOf("old-token", "new-token")

      val snapshot = client.captureConnection()

      snapshot.instanceUrl shouldBe "https://new.example.com"
      snapshot.token shouldBe "new-token"
      snapshot.authFingerprint shouldBe sha256HexPrefix16("new-token")
      snapshot.configGeneration shouldBe 2L
    }

    it("never returns the stable torn (new url, old token) pair while a settings save is writing both stores") {
      // The Codex P1 scenario: performOk has persisted the NEW url (preference store) but not yet
      // the NEW token (secure storage). That torn state is STABLE while the generation is odd — a
      // value-double-read would accept it and leak the old instance's token to the new instance.
      var generationReads = 0
      genFn = {
        generationReads++
        if (generationReads <= 3) 1L else 2L // in-progress window spans several reads, then settles
      }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://new.example.com"
      every { tokens.getToken() } answers {
        if (generationReads <= 3) "old-token" else "new-token"
      }

      val snapshot = client.captureConnection()

      snapshot.instanceUrl shouldBe "https://new.example.com"
      snapshot.token shouldBe "new-token"
      snapshot.token shouldNotBe "old-token"
      snapshot.authFingerprint shouldBe sha256HexPrefix16("new-token")
      snapshot.configGeneration shouldBe 2L
    }

    it("throws UnstableConnectionException when the generation stays odd (update never completes)") {
      genFn = { 7L }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns "tok"

      shouldThrow<UnstableConnectionException> { client.captureConnection() }
    }

    it("throws UnstableConnectionException when the generation keeps advancing between reads") {
      var next = 0L
      // Always even, but never the same value twice in a row.
      genFn = {
        next += 2
        next
      }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com"
      every { tokens.getToken() } returns "tok"

      shouldThrow<UnstableConnectionException> { client.captureConnection() }
    }
  }
})
