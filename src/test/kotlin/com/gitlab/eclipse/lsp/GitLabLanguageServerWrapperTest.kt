package com.gitlab.eclipse.lsp

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicReference

class GitLabLanguageServerWrapperTest : DescribeSpec({
  val wrapper = GitLabLanguageServerWrapper()

  beforeEach {
    // The wrapper's state is process-wide (companion object), so every test starts from empty
    // rather than from whatever the previous one left behind.
    wrapper.unregisterLanguageServer()
  }

  describe("snapshot publication") {
    // Design §21 A25 (structural half).
    it("holds the connection handle in one AtomicReference field and no other state") {
      val stateFields = GitLabLanguageServerWrapper::class.java.declaredFields
        .filterNot { it.isSynthetic }
        .filterNot { it.name == "Companion" }

      stateFields.map { it.type } shouldBe listOf(AtomicReference::class.java)
    }

    // Design §21 A25 (consistency half). Read through the production accessors only.
    it("derives languageServer from currentSnapshot at every register and unregister transition") {
      val proxyA = mockk<GitLabLanguageServer>()
      val handleA = LanguageServerHandle(proxyA, LanguageServerSession())
      val proxyB = mockk<GitLabLanguageServer>()
      val handleB = LanguageServerHandle(proxyB, LanguageServerSession())

      val observed = mutableListOf<Pair<GitLabLanguageServer?, GitLabLanguageServer?>>()
      fun observe() {
        observed += wrapper.languageServer to wrapper.currentSnapshot?.proxy
      }

      observe()
      wrapper.registerLanguageServer(handleA)
      observe()
      wrapper.registerLanguageServer(handleB)
      observe()
      wrapper.unregisterLanguageServer()
      observe()
      wrapper.registerLanguageServer(handleA)
      observe()

      observed shouldBe listOf(
        null to null,
        proxyA to proxyA,
        proxyB to proxyB,
        null to null,
        proxyA to proxyA,
      )
    }
  }

  describe("identity-aware revocation") {
    // Design §21 A28.
    it("leaves the newer connection's snapshot alone when a superseded handle is revoked") {
      val handleA = LanguageServerHandle(mockk(), LanguageServerSession())
      val handleB = LanguageServerHandle(mockk(), LanguageServerSession())
      wrapper.registerLanguageServer(handleA)
      // A captured its own handle above; B takes over before A gets around to revoking.
      wrapper.registerLanguageServer(handleB)

      val revoked = wrapper.unregisterLanguageServer(handleA)

      (revoked to wrapper.currentSnapshot) shouldBe (false to handleB)
    }

    it("clears both accessors when the revoked handle is still the current one") {
      val handleA = LanguageServerHandle(mockk(), LanguageServerSession())
      wrapper.registerLanguageServer(handleA)

      val revoked = wrapper.unregisterLanguageServer(handleA)

      Triple(revoked, wrapper.currentSnapshot, wrapper.languageServer) shouldBe
        Triple(true, null, null)
    }
  }
})
