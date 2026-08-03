package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionMutationException
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.NoteChangedException
import com.gitlab.eclipse.api.UnstableConnectionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import java.io.IOException

class DiscussionWriteFlowTest : DescribeSpec({
  // The registry is a process-wide singleton: reset it around every test so no epoch or
  // activation state leaks between tests (the "registry defaults" tests mutate it).
  beforeEach { DiscussionGenerationRegistry.resetForTest() }
  afterEach { DiscussionGenerationRegistry.resetForTest() }

  val apiClient = mockk<GitLabApiClient>()
  val nodeUrl = "https://gitlab.example.com/"
  val nodeFingerprint = "fp-node"
  val matchingSnapshot = ConnectionSnapshot(
    instanceUrl = "https://gitlab.example.com",
    token = "secret-token",
    authFingerprint = "fp-node",
    configGeneration = 1L,
  )

  beforeEach { clearMocks(apiClient) }

  fun stubCapture(snapshot: ConnectionSnapshot) {
    every { apiClient.captureConnection() } returns snapshot
  }

  describe("runDiscussionWrite connection gate (design §15.3)") {
    it("rejects an instance url mismatch with GateRejected and zero mutate calls") {
      stubCapture(matchingSnapshot.copy(instanceUrl = "https://other.example.com"))
      var mutateCalls = 0

      val outcome = runDiscussionWrite(apiClient, nodeUrl, nodeFingerprint, startEpoch = 0L) {
        mutateCalls += 1
      }

      outcome shouldBe DiscussionWriteOutcome.GateRejected
      mutateCalls shouldBe 0
    }

    it("rejects an auth fingerprint mismatch on the same url with GateRejected and zero mutate calls") {
      stubCapture(matchingSnapshot.copy(token = "other-token", authFingerprint = "fp-other-account"))
      var mutateCalls = 0

      val outcome = runDiscussionWrite(apiClient, nodeUrl, nodeFingerprint, startEpoch = 0L) {
        mutateCalls += 1
      }

      outcome shouldBe DiscussionWriteOutcome.GateRejected
      mutateCalls shouldBe 0
    }

    it("rejects an unstable connection (capture throws) with GateRejected and zero mutate calls") {
      every { apiClient.captureConnection() } throws UnstableConnectionException()
      var mutateCalls = 0

      val outcome = runDiscussionWrite(apiClient, nodeUrl, nodeFingerprint, startEpoch = 0L) {
        mutateCalls += 1
      }

      outcome shouldBe DiscussionWriteOutcome.GateRejected
      mutateCalls shouldBe 0
    }

    it("treats a trailing-slash-only url difference as the same instance and proceeds") {
      // Node url has a trailing slash, snapshot url does not: the gate normalizes.
      stubCapture(matchingSnapshot)
      var mutateCalls = 0

      val outcome = runDiscussionWrite(apiClient, nodeUrl, nodeFingerprint, startEpoch = 0L) {
        mutateCalls += 1
      }

      outcome shouldBe DiscussionWriteOutcome.Success
      mutateCalls shouldBe 1
    }
  }

  describe("runDiscussionWrite pre-send lifecycle validation (design §8.2)") {
    it("aborts when registryActive() is false, with zero mutate calls") {
      stubCapture(matchingSnapshot)
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 0L,
        registryActive = { false },
        registryEpoch = { 0L },
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.Aborted
      mutateCalls shouldBe 0
    }

    it("aborts when registryEpoch() differs from startEpoch, with zero mutate calls") {
      stubCapture(matchingSnapshot)
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 3L,
        registryActive = { true },
        registryEpoch = { 4L },
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.Aborted
      mutateCalls shouldBe 0
    }

    it("aborts when isActive() is false, with zero mutate calls") {
      stubCapture(matchingSnapshot)
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 0L,
        isActive = { false },
        registryActive = { true },
        registryEpoch = { 0L },
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.Aborted
      mutateCalls shouldBe 0
    }

    it("with the gate open and all lifecycle checks healthy, calls mutate once with the pinned snapshot") {
      stubCapture(matchingSnapshot)
      var mutateCalls = 0
      var seenSnapshot: ConnectionSnapshot? = null

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 0L,
        isActive = { true },
        registryActive = { true },
        registryEpoch = { 0L },
      ) { connection ->
        mutateCalls += 1
        seenSnapshot = connection
      }

      outcome shouldBe DiscussionWriteOutcome.Success
      mutateCalls shouldBe 1
      seenSnapshot shouldBeSameInstanceAs matchingSnapshot
      // Pins that mutate receives the snapshot from the ONE gate capture, never a second,
      // re-captured one: the stub returns the same instance on every call, so a regression that
      // called `mutate(apiClient.captureConnection())` would still pass the identity assertion
      // above but fail this count.
      verify(exactly = 1) { apiClient.captureConnection() }
    }
  }

  describe("runDiscussionWrite check precedence") {
    it("reports GateRejected (not Aborted) when the gate mismatches AND the registry is inactive") {
      stubCapture(matchingSnapshot.copy(instanceUrl = "https://other.example.com"))
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 0L,
        registryActive = { false },
        registryEpoch = { 0L },
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.GateRejected
      mutateCalls shouldBe 0
    }

    it("reports Aborted when the gate is open but both the epoch differs and isActive() is false") {
      stubCapture(matchingSnapshot)
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 1L,
        isActive = { false },
        registryActive = { true },
        registryEpoch = { 2L },
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.Aborted
      mutateCalls shouldBe 0
    }
  }

  describe("runDiscussionWrite failure classification wiring") {
    fun runWithFailure(cause: Throwable): DiscussionWriteOutcome {
      stubCapture(matchingSnapshot)
      return runDiscussionWrite(apiClient, nodeUrl, nodeFingerprint, startEpoch = 0L) {
        throw cause
      }
    }

    it("classifies GitLabApiException(403) as Definite") {
      runWithFailure(GitLabApiException(403, "forbidden", null))
        .shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies IOException as Ambiguous") {
      runWithFailure(IOException("reset"))
        .shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
    }

    it("classifies DiscussionMutationException as Definite") {
      runWithFailure(DiscussionMutationException(listOf("rejected")))
        .shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("classifies NoteChangedException as Definite") {
      runWithFailure(NoteChangedException())
        .shouldBeInstanceOf<DiscussionWriteOutcome.Definite>()
    }

    it("carries the SAME throwable instance that mutate threw") {
      val thrown = IOException("reset")

      val outcome = runWithFailure(thrown)

      outcome.shouldBeInstanceOf<DiscussionWriteOutcome.Ambiguous>()
      outcome.cause shouldBeSameInstanceAs thrown
    }
  }

  describe("runDiscussionWrite cancellation") {
    it("rethrows CancellationException instead of classifying it as an outcome") {
      stubCapture(matchingSnapshot)

      shouldThrow<CancellationException> {
        runDiscussionWrite(apiClient, nodeUrl, nodeFingerprint, startEpoch = 0L) {
          throw CancellationException("cancelled")
        }
      }
    }
  }

  describe("runDiscussionWrite registry defaults") {
    it("reads the real DiscussionGenerationRegistry when registryActive/registryEpoch are omitted") {
      stubCapture(matchingSnapshot)
      DiscussionGenerationRegistry.onDeactivate() // active = false; afterEach resetForTest() restores it
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = DiscussionGenerationRegistry.currentEpoch,
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.Aborted
      mutateCalls shouldBe 0
    }

    it("aborts on a stale epoch using the real registryEpoch default (not a constant 0L)") {
      // Proves registryEpoch's default reads DiscussionGenerationRegistry.currentEpoch rather
      // than a constant { 0L }: resetForTest() leaves the epoch at 0, so onActivate() here
      // advances it past 0 while startEpoch stays 0L. A constant-0L default would see
      // registryEpoch() == startEpoch and return Success; only a real, advanced epoch aborts.
      stubCapture(matchingSnapshot)
      DiscussionGenerationRegistry.onActivate() // epoch: 0 -> 1; afterEach resetForTest() restores it
      var mutateCalls = 0

      val outcome = runDiscussionWrite(
        apiClient,
        nodeUrl,
        nodeFingerprint,
        startEpoch = 0L,
      ) { mutateCalls += 1 }

      outcome shouldBe DiscussionWriteOutcome.Aborted
      mutateCalls shouldBe 0
    }
  }

  describe("discussionAuditMessage") {
    val secretMarker = "SECRET-MARKER-BODY"

    it("on Success contains action, normalized url, projectId, mrIid, targetKind, and no exceptionType") {
      val message = discussionAuditMessage(
        action = "createNote",
        instanceUrl = "https://gitlab.example.com",
        projectId = 1234L,
        mrIid = 56L,
        targetKind = "discussion",
        outcome = DiscussionWriteOutcome.Success,
      )

      message shouldContain "createNote"
      message shouldContain "https://gitlab.example.com"
      message shouldContain "1234"
      message shouldContain "56"
      message shouldContain "discussion"
      message shouldNotContain "exceptionType"
    }

    it("on Definite carries exceptionType=GitLabApiException and never the response body") {
      val outcome = DiscussionWriteOutcome.Definite(GitLabApiException(403, secretMarker, null))

      val message = discussionAuditMessage(
        action = "updateNote",
        instanceUrl = "https://gitlab.example.com",
        projectId = 1234L,
        mrIid = 56L,
        targetKind = "note",
        outcome = outcome,
      )

      message shouldContain "exceptionType=GitLabApiException"
      message shouldNotContain secretMarker
    }

    it("on Ambiguous(GraphQlException) never echoes the server error strings (which can carry the comment body)") {
      // GraphQlException.buildMessage interpolates these strings into cause.message, so any
      // implementation that reaches for cause.message fails exactly here.
      val outcome = DiscussionWriteOutcome.Ambiguous(
        GraphQlException(hasDataKey = true, messages = listOf("body was $secretMarker")),
      )

      val message = discussionAuditMessage(
        action = "createNote",
        instanceUrl = "https://gitlab.example.com",
        projectId = 1234L,
        mrIid = 56L,
        targetKind = "mergeRequest",
        outcome = outcome,
      )

      message shouldContain "exceptionType=GraphQlException"
      message shouldNotContain secretMarker
    }

    it("on GateRejected and Aborted produces a line without any exceptionType") {
      for (outcome in listOf(DiscussionWriteOutcome.GateRejected, DiscussionWriteOutcome.Aborted)) {
        val message = discussionAuditMessage(
          action = "destroyNote",
          instanceUrl = "https://gitlab.example.com",
          projectId = 1234L,
          mrIid = 56L,
          targetKind = "note",
          outcome = outcome,
        )

        message shouldContain "destroyNote"
        message shouldNotContain "exceptionType"
      }
    }

    it("normalizes a trailing-slash url in the line") {
      val message = discussionAuditMessage(
        action = "toggleResolve",
        instanceUrl = "https://gitlab.example.com/",
        projectId = 1234L,
        mrIid = 56L,
        targetKind = "discussion",
        outcome = DiscussionWriteOutcome.Success,
      )

      message shouldContain "instanceUrl=https://gitlab.example.com "
      message shouldNotContain "https://gitlab.example.com/"
    }
  }
})
