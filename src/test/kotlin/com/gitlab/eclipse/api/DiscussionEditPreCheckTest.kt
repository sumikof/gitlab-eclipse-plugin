package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabRestNote
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

/**
 * Unit tests for the edit pre-check (task 3, design §13): [restIdFromGid] and
 * [DiscussionWriteService.assertNoteUnchanged]. GitLab's `updateNote` mutation has no optimistic
 * locking, so this REST read-before-write narrows (but cannot close) the TOCTOU window.
 */
class DiscussionEditPreCheckTest : DescribeSpec({
  describe("restIdFromGid") {
    it("extracts the numeric id from a Note gid") {
      restIdFromGid("gid://gitlab/Note/12345") shouldBe "12345"
    }

    it("extracts the numeric id from a DiffNote gid (different type segment)") {
      restIdFromGid("gid://gitlab/DiffNote/98") shouldBe "98"
    }

    it("returns null when the id segment is missing") {
      restIdFromGid("gid://gitlab/Note/").shouldBeNull()
    }

    it("returns null for a bare numeric string") {
      restIdFromGid("12345").shouldBeNull()
    }

    it("returns null for an empty string") {
      restIdFromGid("").shouldBeNull()
    }

    it("returns null when the id segment is not purely numeric") {
      restIdFromGid("gid://gitlab/Note/12a").shouldBeNull()
    }

    it("returns null when there is trailing content after the id") {
      restIdFromGid("gid://gitlab/Note/12345/extra").shouldBeNull()
    }
  }

  describe("assertNoteUnchanged") {
    val graphQlClient = mockk<GitLabGraphQlClient>()
    val apiClient = mockk<GitLabApiClient>()
    val service = DiscussionWriteService(graphQlClient = graphQlClient, apiClient = apiClient)

    beforeEach { clearMocks(apiClient) }

    val connection = ConnectionSnapshot(
      instanceUrl = "https://gitlab.example.com",
      token = "tok-123",
      authFingerprint = "fingerprint",
      configGeneration = 1L,
    )

    val noteGid = "gid://gitlab/Note/12345"

    class Recorded {
      val path: CapturingSlot<String> = slot()
      val query: CapturingSlot<Map<String, String>> = slot()
      val connection: CapturingSlot<ConnectionSnapshot> = slot()
    }

    fun stubFetch(body: String?): Recorded {
      val r = Recorded()
      every {
        apiClient.fetchObject(
          capture(r.path),
          capture(r.query),
          eq(GitLabRestNote::class.java),
          capture(r.connection),
        )
      } returns GitLabRestNote(body)
      return r
    }

    it("returns normally when the fetched body equals the expected body, hitting the exact REST path once") {
      val r = stubFetch("hello")

      service.assertNoteUnchanged(connection, 7, 3, noteGid, "hello")

      r.path.captured shouldBe "/projects/7/merge_requests/3/notes/12345"
      r.connection.captured shouldBeSameInstanceAs connection
      verify(exactly = 1) {
        apiClient.fetchObject(any(), any(), eq(GitLabRestNote::class.java), any())
      }
    }

    it("throws NoteChangedException when the fetched body differs from the expected body") {
      stubFetch("changed by someone else")

      shouldThrow<NoteChangedException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "hello")
      }
    }

    it("throws when the bodies differ only by a trailing space (no trimming)") {
      stubFetch("hello ")

      shouldThrow<NoteChangedException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "hello")
      }
    }

    it("throws when the bodies differ only by line-ending style (no normalization)") {
      stubFetch("hello\r\nworld")

      shouldThrow<NoteChangedException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "hello\nworld")
      }
    }

    it("throws when the fetched body is null, even if the expected body is empty") {
      stubFetch(null)

      shouldThrow<NoteChangedException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "")
      }
    }

    it("throws when Gson yields no note object at all, rather than failing with an NPE") {
      // fetchObject's return type is a non-null generic, but Gson.fromJson produces null for an
      // empty or literal-null 2xx body and bypasses Kotlin's null checks (follow-up #47). An NPE
      // here would be classified Ambiguous and would wrongly warn the user that they may have
      // already posted; the fail-closed answer is "the note is not provably unchanged".
      every {
        apiClient.fetchObject(any(), any(), eq(GitLabRestNote::class.java), any())
      } returns erasedNull()

      shouldThrow<NoteChangedException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "hello")
      }
    }

    it("throws IllegalArgumentException for an unparseable noteGid and never calls fetchObject") {
      shouldThrow<IllegalArgumentException> {
        service.assertNoteUnchanged(connection, 7, 3, "not-a-gid", "hello")
      }

      verify(exactly = 0) {
        apiClient.fetchObject(any(), any(), eq(GitLabRestNote::class.java), any())
      }
    }

    it("propagates a GitLabApiException from fetchObject unchanged") {
      val original = GitLabApiException(404, "not found", null)
      every {
        apiClient.fetchObject(any(), any(), eq(GitLabRestNote::class.java), any())
      } throws original

      val caught = shouldThrow<GitLabApiException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "hello")
      }

      caught shouldBeSameInstanceAs original
    }

    it("never includes either body in NoteChangedException's message") {
      stubFetch("SECRET-MARKER-SERVER")

      val e = shouldThrow<NoteChangedException> {
        service.assertNoteUnchanged(connection, 7, 3, noteGid, "SECRET-MARKER-EXPECTED")
      }

      e.message.shouldNotContain("SECRET-MARKER-SERVER")
      e.message.shouldNotContain("SECRET-MARKER-EXPECTED")
    }
  }
})

/**
 * Produces a null typed as a non-null `T`. Casting to an unbounded type parameter is unchecked and
 * erased, so no null check is emitted — which is exactly how Gson smuggles a null through
 * `fetchObject`'s non-null generic return type at runtime. This lets the test reproduce that
 * situation, which no ordinary Kotlin expression can express.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> erasedNull(): T = null as T
