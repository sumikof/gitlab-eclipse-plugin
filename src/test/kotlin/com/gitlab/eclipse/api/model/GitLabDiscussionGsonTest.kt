package com.gitlab.eclipse.api.model

import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * Proves that Gson leaves absent GraphQL keys as `null` (even for non-null-declared fields on the
 * DTOs) and that the normalizers in [GitLabDiscussion.kt], [GitLabNote.kt] and
 * [GitLabNotePosition.kt] repair every such gap. This is the reason task 3 exists.
 */
class GitLabDiscussionGsonTest : DescribeSpec({
  val gson = Gson()

  describe("a fully-populated discussion JSON") {
    it("normalizes to the expected domain object") {
      val json = """
        {
          "replyId": "reply-1",
          "createdAt": "2026-01-01T00:00:00Z",
          "resolved": true,
          "resolvable": true,
          "notes": {
            "pageInfo": { "hasNextPage": true, "endCursor": "cursor-1" },
            "nodes": [
              {
                "id": "note-1",
                "createdAt": "2026-01-01T00:00:00Z",
                "system": false,
                "author": { "username": "alice" },
                "body": "hello",
                "userPermissions": { "resolveNote": true, "adminNote": false, "createNote": true },
                "position": {
                  "positionType": "text",
                  "newPath": "b.txt",
                  "oldPath": "a.txt",
                  "newLine": 10,
                  "oldLine": 5
                }
              }
            ]
          }
        }
      """.trimIndent()

      val dto = gson.fromJson(json, DiscussionDto::class.java)
      val discussion = dto.toDomain()

      discussion shouldBe GitLabDiscussion(
        replyId = "reply-1",
        createdAt = "2026-01-01T00:00:00Z",
        resolved = true,
        resolvable = true,
        hasMoreNotes = true,
        notes = listOf(
          GitLabNote(
            id = "note-1",
            createdAt = "2026-01-01T00:00:00Z",
            system = false,
            authorUsername = "alice",
            body = "hello",
            permissions = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true),
            position = GitLabNotePosition(
              positionType = "text",
              newPath = "b.txt",
              oldPath = "a.txt",
              newLine = 10,
              oldLine = 5,
            ),
          ),
        ),
      )
    }
  }

  describe("a discussion JSON with notes entirely absent") {
    it("normalizes without throwing") {
      val json = """{"replyId": "reply-1", "createdAt": "t", "resolved": false, "resolvable": true}"""

      val dto = gson.fromJson(json, DiscussionDto::class.java)
      val discussion = dto.toDomain()

      discussion.notes shouldBe emptyList()
      discussion.hasMoreNotes shouldBe false
    }
  }

  describe("a note JSON with userPermissions absent") {
    it("yields all-false permissions") {
      val json = """
        {
          "id": "note-1",
          "createdAt": "t",
          "system": false,
          "author": { "username": "alice" },
          "body": "hello"
        }
      """.trimIndent()

      val note = gson.fromJson(json, NoteDto::class.java).toDomain()

      note.permissions shouldBe GitLabNotePermissions(resolveNote = false, adminNote = false, createNote = false)
    }
  }

  describe("a note JSON with position absent") {
    it("yields position == null") {
      val json = """{"id": "note-1", "createdAt": "t", "system": false, "body": "hello"}"""

      val note = gson.fromJson(json, NoteDto::class.java).toDomain()

      note.position shouldBe null
    }
  }

  describe("a note JSON whose position has newLine but no oldLine") {
    it("keeps newLine and leaves oldLine null") {
      val json = """
        {
          "id": "note-1",
          "createdAt": "t",
          "system": false,
          "body": "hello",
          "position": { "positionType": "text", "newPath": "b.txt", "newLine": 7 }
        }
      """.trimIndent()

      val note = gson.fromJson(json, NoteDto::class.java).toDomain()

      note.position?.newLine shouldBe 7
      note.position?.oldLine shouldBe null
    }
  }

  describe("a position JSON with positionType absent") {
    it("defaults to \"text\"") {
      val json = """{"id": "note-1", "createdAt": "t", "system": false, "body": "hello", "position": {}}"""

      val note = gson.fromJson(json, NoteDto::class.java).toDomain()

      note.position?.positionType shouldBe "text"
    }
  }
})
