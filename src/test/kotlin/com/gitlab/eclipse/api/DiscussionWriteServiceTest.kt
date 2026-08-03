package com.gitlab.eclipse.api

import com.google.gson.JsonSyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration

/**
 * Unit tests for [DiscussionWriteService] (task 2): the four mutation constants, their variable
 * maps, the L3 payload-error inspection, and exception passthrough. The retry/classification
 * behaviour built on top of these exceptions lives in `classifyWriteFailure` and is tested there,
 * not here.
 */
class DiscussionWriteServiceTest : DescribeSpec({
  val graphQlClient = mockk<GitLabGraphQlClient>()
  // apiClient is passed explicitly (never exercised by these tests, which predate task 3's
  // assertNoteUnchanged) because the constructor's default is `service()`, which throws outside a
  // started Koin context — the same reason GitLabGraphQlClientTest always supplies both of its
  // service()-defaulted constructor args explicitly.
  val service = DiscussionWriteService(graphQlClient, mockk<GitLabApiClient>())

  beforeEach { clearMocks(graphQlClient) }

  val connection = ConnectionSnapshot(
    instanceUrl = "https://gitlab.example.com",
    token = "tok-123",
    authFingerprint = "fingerprint",
    configGeneration = 1L,
  )

  val mrGid = "gid://gitlab/MergeRequest/1234"
  val noteGid = "gid://gitlab/Note/7"

  class Recorded {
    val query: CapturingSlot<String> = slot()
    val variables: CapturingSlot<Map<String, Any?>> = slot()
    val connection: CapturingSlot<ConnectionSnapshot> = slot()
    val timeout: CapturingSlot<Duration> = slot()
  }

  fun stubCreateNote(payload: MutationPayloadDto?): Recorded {
    val r = Recorded()
    every {
      graphQlClient.execute(
        capture(r.query),
        capture(r.variables),
        eq(CreateNoteData::class.java),
        capture(r.connection),
        capture(r.timeout),
      )
    } returns CreateNoteData(payload)
    return r
  }

  fun stubToggleResolve(payload: MutationPayloadDto?): Recorded {
    val r = Recorded()
    every {
      graphQlClient.execute(
        capture(r.query),
        capture(r.variables),
        eq(ToggleResolveData::class.java),
        capture(r.connection),
        capture(r.timeout),
      )
    } returns ToggleResolveData(payload)
    return r
  }

  fun stubUpdateNote(payload: MutationPayloadDto?): Recorded {
    val r = Recorded()
    every {
      graphQlClient.execute(
        capture(r.query),
        capture(r.variables),
        eq(UpdateNoteData::class.java),
        capture(r.connection),
        capture(r.timeout),
      )
    } returns UpdateNoteData(payload)
    return r
  }

  fun stubDestroyNote(payload: MutationPayloadDto?): Recorded {
    val r = Recorded()
    every {
      graphQlClient.execute(
        capture(r.query),
        capture(r.variables),
        eq(DestroyNoteData::class.java),
        capture(r.connection),
        capture(r.timeout),
      )
    } returns DestroyNoteData(payload)
    return r
  }

  data class WriteCase(
    val name: String,
    val mutation: String,
    val expectedVariables: Map<String, Any?>,
    val stub: (MutationPayloadDto?) -> Recorded,
    val call: () -> Unit,
  )

  val cases = listOf(
    WriteCase(
      name = "createNote",
      mutation = DiscussionWriteService.CREATE_NOTE_MUTATION,
      expectedVariables = mapOf(
        "issuableId" to mrGid,
        "body" to "hello",
        "replyId" to "reply-1",
        "mergeRequestDiffHeadSha" to "sha-1",
      ),
      stub = { stubCreateNote(it) },
      call = { service.createNote(connection, mrGid, "hello", "reply-1", "sha-1") },
    ),
    WriteCase(
      name = "toggleResolve",
      mutation = DiscussionWriteService.TOGGLE_RESOLVE_MUTATION,
      expectedVariables = mapOf("replyId" to "reply-1", "resolved" to true),
      stub = { stubToggleResolve(it) },
      call = { service.toggleResolve(connection, "reply-1", true) },
    ),
    WriteCase(
      name = "updateNote",
      mutation = DiscussionWriteService.UPDATE_NOTE_MUTATION,
      expectedVariables = mapOf("noteId" to noteGid, "body" to "edited"),
      stub = { stubUpdateNote(it) },
      call = { service.updateNote(connection, noteGid, "edited") },
    ),
    WriteCase(
      name = "destroyNote",
      mutation = DiscussionWriteService.DESTROY_NOTE_MUTATION,
      expectedVariables = mapOf("noteId" to noteGid),
      stub = { stubDestroyNote(it) },
      call = { service.destroyNote(connection, noteGid) },
    ),
  )

  cases.forEach { case ->
    describe(case.name) {
      it("sends its *_MUTATION constant as the query, unchanged") {
        val r = case.stub(MutationPayloadDto(emptyList()))

        case.call()

        r.query.captured shouldBe case.mutation
      }

      it("sends exactly the expected variables map, no extra and no missing key") {
        val r = case.stub(MutationPayloadDto(emptyList()))

        case.call()

        r.variables.captured shouldBe case.expectedVariables
      }

      it("passes WRITE_TIMEOUT and the supplied connection through to execute") {
        val r = case.stub(MutationPayloadDto(emptyList()))

        case.call()

        r.timeout.captured shouldBe DiscussionWriteService.WRITE_TIMEOUT
        r.connection.captured shouldBe connection
      }

      it("throws DiscussionMutationException carrying the payload errors when they are non-empty") {
        case.stub(MutationPayloadDto(listOf("Note body is too long")))

        val e = shouldThrow<DiscussionMutationException> { case.call() }

        e.messages shouldBe listOf("Note body is too long")
      }

      it("returns normally when the payload errors list is empty") {
        case.stub(MutationPayloadDto(emptyList()))

        case.call()
      }

      it("returns normally when the payload errors list is null (Gson may omit the field)") {
        case.stub(MutationPayloadDto(null))

        case.call()
      }

      it("drops null elements from the payload errors and throws with the remainder") {
        case.stub(MutationPayloadDto(listOf(null, "boom")))

        val e = shouldThrow<DiscussionMutationException> { case.call() }

        e.messages shouldBe listOf("boom")
      }

      // A missing payload is Ambiguous, NOT Definite: a 2xx response carrying no payload object
      // does not prove the mutation never ran, so offering [Retry] could post the comment twice.
      // JsonSyntaxException is what classifyWriteFailure maps to Ambiguous.
      it("throws JsonSyntaxException — never DiscussionMutationException — when the mutation payload is absent") {
        case.stub(null)

        val e = shouldThrow<JsonSyntaxException> { case.call() }

        e.message shouldBe DiscussionWriteService.MISSING_PAYLOAD_MESSAGE
      }
    }
  }

  describe("createNote null-valued variables") {
    it("keeps the replyId key present with a null value for an MR-level comment") {
      val r = stubCreateNote(MutationPayloadDto(emptyList()))

      service.createNote(connection, mrGid, "hello", null, "sha-1")

      r.variables.captured.containsKey("replyId") shouldBe true
      r.variables.captured["replyId"] shouldBe null
    }

    it("keeps the mergeRequestDiffHeadSha key present with a null value when the MR sha is unknown") {
      val r = stubCreateNote(MutationPayloadDto(emptyList()))

      service.createNote(connection, mrGid, "hello", "reply-1", null)

      r.variables.captured.containsKey("mergeRequestDiffHeadSha") shouldBe true
      r.variables.captured["mergeRequestDiffHeadSha"] shouldBe null
    }
  }

  describe("toggleResolve target state") {
    it("passes resolved = false through unmodified — resolve is a target state, not a toggle") {
      val r = stubToggleResolve(MutationPayloadDto(emptyList()))

      service.toggleResolve(connection, "reply-1", false)

      r.variables.captured["resolved"] shouldBe false
    }
  }

  describe("exception passthrough") {
    it("propagates an L2 GraphQlException unchanged, preserving hasDataKey for the Ambiguous classification") {
      val original = GraphQlException(hasDataKey = true, messages = listOf("resolver failed"))
      every {
        graphQlClient.execute(any(), any(), eq(CreateNoteData::class.java), any(), any())
      } throws original

      val caught = shouldThrow<GraphQlException> {
        service.createNote(connection, mrGid, "hello", null, null)
      }

      caught shouldBeSameInstanceAs original
      caught.hasDataKey shouldBe true
    }

    it("propagates a GitLabApiException unchanged") {
      val original = GitLabApiException(500, "boom", null)
      every {
        graphQlClient.execute(any(), any(), eq(CreateNoteData::class.java), any(), any())
      } throws original

      val caught = shouldThrow<GitLabApiException> {
        service.createNote(connection, mrGid, "hello", null, null)
      }

      caught shouldBeSameInstanceAs original
    }
  }

  describe("body confidentiality") {
    it("keeps payload error text (which can echo the note body) out of the exception message") {
      stubCreateNote(MutationPayloadDto(listOf("body was 'SECRET-MARKER'")))

      val e = shouldThrow<DiscussionMutationException> {
        service.createNote(connection, mrGid, "SECRET-MARKER", null, null)
      }

      e.message shouldNotContain "SECRET-MARKER"
    }
  }

  describe("mutation constants") {
    val allMutations = listOf(
      DiscussionWriteService.CREATE_NOTE_MUTATION,
      DiscussionWriteService.TOGGLE_RESOLVE_MUTATION,
      DiscussionWriteService.UPDATE_NOTE_MUTATION,
      DiscussionWriteService.DESTROY_NOTE_MUTATION,
    )

    it("select { errors } only — no noteDetails fragment (approved deviation from design §10.3)") {
      allMutations.forEach { mutation ->
        mutation shouldNotContain "noteDetails"
        mutation shouldNotContain "fragment"
        mutation shouldNotContain "note {"
        mutation shouldContain "errors"
      }
    }

    it("declares the CreateNote variable and input-field names exactly as in the reference") {
      val m = DiscussionWriteService.CREATE_NOTE_MUTATION
      m shouldContain "mutation CreateNote(" +
        "\$issuableId: NoteableID!, \$body: String!, \$replyId: DiscussionID, \$mergeRequestDiffHeadSha: String)"
      m shouldContain "createNote(input: { noteableId: \$issuableId, body: \$body, " +
        "discussionId: \$replyId, mergeRequestDiffHeadSha: \$mergeRequestDiffHeadSha })"
    }

    it("declares the DiscussionToggleResolve variable and input-field names exactly as in the reference") {
      val m = DiscussionWriteService.TOGGLE_RESOLVE_MUTATION
      m shouldContain "mutation DiscussionToggleResolve(\$replyId: DiscussionID!, \$resolved: Boolean!)"
      m shouldContain "discussionToggleResolve(input: { id: \$replyId, resolve: \$resolved })"
    }

    it("declares the UpdateNoteBody variable and input-field names exactly as in the reference") {
      val m = DiscussionWriteService.UPDATE_NOTE_MUTATION
      m shouldContain "mutation UpdateNoteBody(\$noteId: NoteID!, \$body: String)"
      m shouldContain "updateNote(input: { id: \$noteId, body: \$body })"
    }

    it("declares the DeleteNote variable name and the destroyNote payload field exactly as in the reference") {
      val m = DiscussionWriteService.DESTROY_NOTE_MUTATION
      m shouldContain "mutation DeleteNote(\$noteId: NoteID!)"
      m shouldContain "destroyNote(input: { id: \$noteId })"
    }
  }
})
