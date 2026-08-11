package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.runtime.ILog
import java.io.IOException

/**
 * The audit line must not be load-bearing. `DiscussionWriteLauncher` classifies **any** throwable
 * escaping the injected `write` as [DiscussionWriteOutcome.Definite] — "nothing was transmitted,
 * `[Retry]` is safe" — and that premise only holds while nothing after the mutation can throw.
 * [auditedDiscussionWrite] logs after the mutation may already have committed, so a failing logger
 * must never be able to turn a Success into a reported rejection, or an Ambiguous into a retryable
 * Definite that duplicates a posted comment.
 */
class AuditedDiscussionWriteTest : DescribeSpec({
  // The registry is a process-wide singleton; reset it so the pre-send lifecycle re-check inside
  // runDiscussionWrite sees a clean epoch.
  beforeEach { DiscussionGenerationRegistry.resetForTest() }
  afterEach { DiscussionGenerationRegistry.resetForTest() }

  val apiClient = mockk<GitLabApiClient>()
  val log = mockk<ILog>()
  val target = DiscussionWriteTarget(
    instanceUrl = "https://gitlab.example.com/",
    authFingerprint = "fp-node",
    projectId = 42L,
    mrIid = 7L,
  )
  val key = DiscussionWriteKey.forNote(target.instanceUrl, target.authFingerprint, "gid://gitlab/Note/1")

  // The gate compares the instance url inside captureConnectionIf, before the credential is read
  // (issue #49), so the stub honors the predicate the way the real client does.
  fun stubCapture(snapshot: ConnectionSnapshot) {
    every { apiClient.captureConnectionIf(any()) } answers {
      val accept = firstArg<(String) -> Boolean>()
      if (accept(snapshot.instanceUrl)) snapshot else null
    }
  }

  beforeEach {
    clearMocks(apiClient, log)
    stubCapture(
      ConnectionSnapshot(
        instanceUrl = "https://gitlab.example.com",
        token = "secret-token",
        authFingerprint = "fp-node",
        configGeneration = 1L,
      ),
    )
  }

  fun write(mutate: (ConnectionSnapshot) -> Unit) = auditedDiscussionWrite(
    apiClient,
    log,
    action = "editNote",
    target = target,
    key = key,
    startEpoch = DiscussionGenerationRegistry.currentEpoch,
    mutate = mutate,
  )

  describe("auditedDiscussionWrite") {
    it("emits one secret-free audit line carrying the outcome") {
      val logged = mutableListOf<String>()
      every { log.info(capture(logged)) } returns Unit

      write { } shouldBe DiscussionWriteOutcome.Success

      logged shouldHaveSize 1
      logged.single() shouldContain "action=editNote"
      logged.single() shouldContain "targetKind=note"
      logged.single() shouldContain "outcome=success"
    }

    it("returns Success even when the audit log throws") {
      every { log.info(any<String>()) } throws IllegalStateException("log backend down")

      // Without the containment this would escape and the launcher would report a committed
      // write as a rejection, offering [Retry] for a comment that is already posted.
      write { } shouldBe DiscussionWriteOutcome.Success
    }

    it("returns the Ambiguous outcome unchanged when the audit log throws") {
      every { log.info(any<String>()) } throws IllegalStateException("log backend down")
      val cause = IOException("connection reset")

      val outcome = write { throw cause }

      // Ambiguous, NOT the Definite the launcher would have synthesized from an escaped throwable:
      // Definite offers [Retry], and this mutation may well have committed.
      outcome.shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
      outcome.cause shouldBeSameInstanceAs cause
    }

    it("returns GateRejected unchanged when the audit log throws") {
      every { log.info(any<String>()) } throws IllegalStateException("log backend down")
      stubCapture(
        ConnectionSnapshot(
          instanceUrl = "https://other.example.com",
          token = "other-token",
          authFingerprint = "fp-other",
          configGeneration = 1L,
        ),
      )

      write { error("mutate must not run behind a closed gate") } shouldBe DiscussionWriteOutcome.GateRejected
    }
  }
})
