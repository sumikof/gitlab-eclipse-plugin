package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.DiscussionDto
import com.gitlab.eclipse.api.model.NoteAuthorDto
import com.gitlab.eclipse.api.model.NoteConnectionDto
import com.gitlab.eclipse.api.model.NoteDto
import com.gitlab.eclipse.api.model.PageInfoDto
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for [DiscussionService.getDiscussions] (task 5): paging, deadline handling, the
 * carried-out `canCreateNote`, note filtering/ordering, and envelope errors. Task 4's protocol
 * constants and identifier construction are covered separately in [DiscussionServiceTest].
 */
class DiscussionServicePagingTest : DescribeSpec({
  val graphQlClient = mockk<GitLabGraphQlClient>()
  val service = DiscussionService(graphQlClient)

  extensions(LoggingKotestExtension)

  beforeEach { clearMocks(graphQlClient) }

  val connection = ConnectionSnapshot(
    instanceUrl = "https://gitlab.example.com",
    token = "tok-123",
    authFingerprint = "fingerprint",
    configGeneration = 1L,
  )
  val namespaceWithPath = "group/project"
  val mrIid = 42L
  val deadline = Duration.ofSeconds(60)

  fun noteDto(id: String, system: Boolean = false, createdAt: String = "2026-01-01T00:00:00Z") =
    NoteDto(
      id = id,
      createdAt = createdAt,
      system = system,
      author = NoteAuthorDto("alice"),
      body = "body-$id",
      userPermissions = null,
      position = null,
    )

  fun discussionDto(
    replyId: String,
    createdAt: String = "2026-01-01T00:00:00Z",
    notes: List<NoteDto?> = listOf(noteDto("$replyId-note")),
    notesHasNextPage: Boolean? = false,
  ) = DiscussionDto(
    replyId = replyId,
    createdAt = createdAt,
    resolved = false,
    resolvable = true,
    notes = NoteConnectionDto(pageInfo = PageInfoDto(notesHasNextPage, null), nodes = notes),
  )

  fun queryData(
    canCreateNote: Boolean? = true,
    hasNextPage: Boolean? = false,
    endCursor: String? = null,
    nodes: List<DiscussionDto?> = listOf(discussionDto("r1")),
    discussionsPresent: Boolean = true,
    mergeRequestPresent: Boolean = true,
    projectPresent: Boolean = true,
  ): DiscussionsQueryData {
    if (!projectPresent) return DiscussionsQueryData(project = null)
    if (!mergeRequestPresent) return DiscussionsQueryData(project = ProjectDto(id = "p1", mergeRequest = null))
    val discussionConnection = if (discussionsPresent) {
      DiscussionConnectionDto(pageInfo = PageInfoDto(hasNextPage, endCursor), nodes = nodes)
    } else {
      null
    }
    return DiscussionsQueryData(
      project = ProjectDto(
        id = "p1",
        mergeRequest = MergeRequestDto(
          userPermissions = MrPermissionsDto(canCreateNote),
          discussions = discussionConnection,
        ),
      ),
    )
  }

  data class Recorded(val cursor: Any?, val connection: ConnectionSnapshot, val timeout: Duration)

  fun stub(vararg pages: DiscussionsQueryData): MutableList<Recorded> {
    val recorded = mutableListOf<Recorded>()
    var call = 0
    every {
      graphQlClient.execute(any(), any(), DiscussionsQueryData::class.java, any(), any())
    } answers {
      val variables = secondArg<Map<String, Any?>>()
      recorded += Recorded(variables["afterCursor"], arg<ConnectionSnapshot>(3), arg<Duration>(4))
      val page = pages[call]
      call++
      page
    }
    return recorded
  }

  fun fetch(clock: () -> Long = { 0L }, isActive: () -> Boolean = { true }, budget: Duration = deadline) =
    service.getDiscussions(connection, namespaceWithPath, mrIid, budget, clock, isActive)

  describe("paging") {
    it("issues one execute call and returns truncation == null when hasNextPage is false") {
      stub(queryData(hasNextPage = false))

      val result = fetch()

      result.truncation shouldBe null
      verify(exactly = 1) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }

    it("follows endCursor to a second page and concatenates results in order") {
      val recorded = stub(
        queryData(hasNextPage = true, endCursor = "cursor-1", nodes = listOf(discussionDto("r1"))),
        queryData(hasNextPage = false, nodes = listOf(discussionDto("r2"))),
      )

      val result = fetch()

      result.discussions.map { it.replyId } shouldContainExactly listOf("r1", "r2")
      recorded[1].cursor shouldBe "cursor-1"
    }

    it("passes afterCursor == null on the first call") {
      val recorded = stub(queryData(hasNextPage = false))

      fetch()

      recorded[0].cursor shouldBe null
    }

    it("returns fetched discussions as MISSING_CURSOR, one request, when hasNextPage=true with a null endCursor") {
      stub(queryData(hasNextPage = true, endCursor = null, nodes = listOf(discussionDto("r1"))))

      val result = fetch()

      result.truncation shouldBe TruncationReason.MISSING_CURSOR
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1")
      verify(exactly = 1) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }

    it("returns fetched discussions as MISSING_CURSOR, one request, when hasNextPage=true with a blank endCursor") {
      stub(queryData(hasNextPage = true, endCursor = "  ", nodes = listOf(discussionDto("r1"))))

      val result = fetch()

      result.truncation shouldBe TruncationReason.MISSING_CURSOR
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1")
      verify(exactly = 1) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }

    it("stops with MISSING_CURSOR after two calls when a page returns the same cursor it was requested with") {
      stub(
        queryData(hasNextPage = true, endCursor = "cursor-a", nodes = listOf(discussionDto("r1"))),
        // Page 2 is requested with cursor-a and reports cursor-a again: re-sending it would
        // re-fetch this same page (duplicating its discussions) up to the page cap.
        queryData(hasNextPage = true, endCursor = "cursor-a", nodes = listOf(discussionDto("r2"))),
      )

      val result = fetch()

      result.truncation shouldBe TruncationReason.MISSING_CURSOR
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1", "r2")
      verify(exactly = 2) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }

    it("stops with MISSING_CURSOR and no duplicated discussions on a cursor cycle (A -> B -> A)") {
      stub(
        queryData(hasNextPage = true, endCursor = "cursor-a", nodes = listOf(discussionDto("r1"))),
        queryData(hasNextPage = true, endCursor = "cursor-b", nodes = listOf(discussionDto("r2"))),
        queryData(hasNextPage = true, endCursor = "cursor-a", nodes = listOf(discussionDto("r3"))),
      )

      val result = fetch()

      result.truncation shouldBe TruncationReason.MISSING_CURSOR
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1", "r2", "r3")
      verify(exactly = 3) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }

    it("still pages a normally advancing cursor sequence to completion with truncation == null") {
      val recorded = stub(
        queryData(hasNextPage = true, endCursor = "cursor-1", nodes = listOf(discussionDto("r1"))),
        queryData(hasNextPage = true, endCursor = "cursor-2", nodes = listOf(discussionDto("r2"))),
        queryData(hasNextPage = false, nodes = listOf(discussionDto("r3"))),
      )

      val result = fetch()

      result.truncation shouldBe null
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1", "r2", "r3")
      recorded.map { it.cursor } shouldContainExactly listOf(null, "cursor-1", "cursor-2")
    }

    it("stops at MAX_DISCUSSION_PAGES, calling execute exactly 20 times, with truncation == PAGE_LIMIT") {
      val pages = (1..DiscussionService.MAX_DISCUSSION_PAGES).map { page ->
        queryData(hasNextPage = true, endCursor = "cursor-$page", nodes = listOf(discussionDto("r$page")))
      }
      stub(*pages.toTypedArray())

      val result = fetch()

      result.truncation shouldBe TruncationReason.PAGE_LIMIT
      result.discussions.size shouldBe DiscussionService.MAX_DISCUSSION_PAGES
      verify(exactly = DiscussionService.MAX_DISCUSSION_PAGES) {
        graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any())
      }
    }

    it("never pages the inner notes connection, but surfaces hasMoreNotes on the discussion") {
      stub(
        queryData(
          hasNextPage = false,
          nodes = listOf(discussionDto("r1", notesHasNextPage = true)),
        ),
      )

      val result = fetch()

      verify(exactly = 1) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
      result.discussions.single().hasMoreNotes shouldBe true
    }
  }

  describe("deadline") {
    it("stops before the second call once elapsed time has passed the deadline, without throwing") {
      var nanos = 0L
      val clock = { nanos }
      every {
        graphQlClient.execute(any(), any(), DiscussionsQueryData::class.java, any(), any())
      } answers {
        nanos += Duration.ofSeconds(10).toNanos()
        queryData(hasNextPage = true, endCursor = "cursor-1", nodes = listOf(discussionDto("r1")))
      }
      val shortDeadline = Duration.ofSeconds(5)

      val result = service.getDiscussions(connection, namespaceWithPath, mrIid, shortDeadline, clock)

      result.truncation shouldBe TruncationReason.DEADLINE
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1")
      verify(exactly = 1) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }

    it("caps each request's timeout to min(30s, remaining budget)") {
      var nanos = 0L
      val clock = { nanos }
      val recordedTimeouts = mutableListOf<Duration>()
      val bigDeadline = Duration.ofSeconds(60)
      every {
        graphQlClient.execute(any(), any(), DiscussionsQueryData::class.java, any(), any())
      } answers {
        recordedTimeouts += arg<Duration>(4)
        nanos += Duration.ofSeconds(45).toNanos()
        if (recordedTimeouts.size == 1) queryData(hasNextPage = true, endCursor = "cursor-1") else queryData(hasNextPage = false)
      }

      service.getDiscussions(connection, namespaceWithPath, mrIid, bigDeadline, clock)

      // call 1 at t=0: remaining == 60s, capped at the 30s default.
      recordedTimeouts[0] shouldBe Duration.ofSeconds(30)
      // call 2 at t=45s: remaining == 15s, below the 30s default.
      recordedTimeouts[1] shouldBe Duration.ofSeconds(15)
    }

    it("swallows an HttpTimeoutException from a request given less than 30s, returning fetched pages as DEADLINE") {
      var nanos = 0L
      val clock = { nanos }
      var call = 0
      every {
        graphQlClient.execute(any(), any(), DiscussionsQueryData::class.java, any(), any())
      } answers {
        call++
        if (call == 1) {
          nanos += Duration.ofSeconds(50).toNanos()
          queryData(hasNextPage = true, endCursor = "cursor-1", nodes = listOf(discussionDto("r1")))
        } else {
          throw HttpTimeoutException("request timed out")
        }
      }
      val shortDeadline = Duration.ofSeconds(60)

      val result = service.getDiscussions(connection, namespaceWithPath, mrIid, shortDeadline, clock)

      result.truncation shouldBe TruncationReason.DEADLINE
      result.discussions.map { it.replyId } shouldContainExactly listOf("r1")
    }

    it("propagates an HttpTimeoutException from a request given the full 30s") {
      every {
        graphQlClient.execute(any(), any(), DiscussionsQueryData::class.java, any(), any())
      } throws HttpTimeoutException("request timed out")

      shouldThrow<HttpTimeoutException> { fetch() }
    }

    it("propagates a non-timeout exception (GraphQlException) unchanged regardless of remaining budget") {
      every {
        graphQlClient.execute(any(), any(), DiscussionsQueryData::class.java, any(), any())
      } throws GraphQlException(hasDataKey = true, messages = listOf("boom"))

      shouldThrow<GraphQlException> { fetch() }
    }

    it("throws CancellationException and issues no execute call once isActive returns false") {
      stub(queryData(hasNextPage = false))

      shouldThrow<CancellationException> {
        fetch(isActive = { false })
      }

      verify(exactly = 0) { graphQlClient.execute(any(), any(), any<Class<*>>(), any(), any()) }
    }
  }

  describe("canCreateNote") {
    it("carries true from the first page") {
      stub(queryData(canCreateNote = true, hasNextPage = false))

      fetch().canCreateNote shouldBe true
    }

    it("is true for a merge request with zero discussions") {
      stub(queryData(canCreateNote = true, hasNextPage = false, nodes = emptyList()))

      val result = fetch()

      result.canCreateNote shouldBe true
      result.discussions shouldBe emptyList()
    }

    it("is false when userPermissions is null") {
      val data = DiscussionsQueryData(
        project = ProjectDto(
          id = "p1",
          mergeRequest = MergeRequestDto(
            userPermissions = null,
            discussions = DiscussionConnectionDto(pageInfo = PageInfoDto(false, null), nodes = emptyList()),
          ),
        ),
      )
      stub(data)

      fetch().canCreateNote shouldBe false
    }

    it("comes from the first page even when a later page's value differs") {
      stub(
        queryData(canCreateNote = true, hasNextPage = true, endCursor = "cursor-1"),
        queryData(canCreateNote = false, hasNextPage = false),
      )

      fetch().canCreateNote shouldBe true
    }
  }

  describe("filtering and ordering") {
    it("drops system notes while keeping non-system notes in order") {
      stub(
        queryData(
          hasNextPage = false,
          nodes = listOf(
            discussionDto(
              "r1",
              notes = listOf(
                noteDto("n1", system = true),
                noteDto("n2", system = false),
                noteDto("n3", system = false),
              ),
            ),
          ),
        ),
      )

      val result = fetch()

      result.discussions.single().notes.map { it.id } shouldContainExactly listOf("n2", "n3")
    }

    it("drops a discussion whose notes are all system notes") {
      stub(
        queryData(
          hasNextPage = false,
          nodes = listOf(discussionDto("r1", notes = listOf(noteDto("n1", system = true)))),
        ),
      )

      fetch().discussions shouldBe emptyList()
    }

    it("drops a discussion with no notes at all") {
      stub(
        queryData(
          hasNextPage = false,
          nodes = listOf(discussionDto("r1", notes = emptyList())),
        ),
      )

      fetch().discussions shouldBe emptyList()
    }

    it("returns discussions sorted by createdAt ascending given out-of-order input") {
      stub(
        queryData(
          hasNextPage = false,
          nodes = listOf(
            discussionDto("late", createdAt = "2026-03-01T00:00:00Z"),
            discussionDto("early", createdAt = "2026-01-01T00:00:00Z"),
            discussionDto("mid", createdAt = "2026-02-01T00:00:00Z"),
          ),
        ),
      )

      val result = fetch()

      result.discussions.map { it.replyId } shouldContainExactly listOf("early", "mid", "late")
    }
  }

  describe("envelope handling") {
    it("throws GraphQlException when project is null") {
      stub(queryData(projectPresent = false))

      shouldThrow<GraphQlException> { fetch() }
    }

    it("throws GraphQlException when project.mergeRequest is null") {
      stub(queryData(mergeRequestPresent = false))

      shouldThrow<GraphQlException> { fetch() }
    }

    it("returns an empty list, truncation == null, and the fetched canCreateNote when discussions is null") {
      stub(queryData(canCreateNote = true, discussionsPresent = false))

      val result = fetch()

      result.discussions shouldBe emptyList()
      result.truncation shouldBe null
      result.canCreateNote shouldBe true
    }
  }

  describe("pinning") {
    it("passes the same ConnectionSnapshot to every execute call, including the second page's") {
      val recorded = stub(
        queryData(hasNextPage = true, endCursor = "cursor-1"),
        queryData(hasNextPage = false),
      )

      fetch()

      recorded.size shouldBe 2
      recorded[0].connection shouldBe connection
      recorded[1].connection shouldBe connection
    }
  }
})
