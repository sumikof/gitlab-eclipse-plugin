package com.gitlab.eclipse.api.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class GitLabDiscussionNormalizationTest : DescribeSpec({

  describe("NotePositionDto.toDomain") {
    it("defaults a missing positionType to \"text\"") {
      val position = NotePositionDto(
        positionType = null,
        newPath = null,
        oldPath = null,
        newLine = null,
        oldLine = null,
      ).toDomain()

      position.positionType shouldBe "text"
    }

    it("keeps newPath/oldPath/newLine/oldLine null when absent") {
      val position = NotePositionDto(
        positionType = "text",
        newPath = null,
        oldPath = null,
        newLine = null,
        oldLine = null,
      ).toDomain()

      position.newPath shouldBe null
      position.oldPath shouldBe null
      position.newLine shouldBe null
      position.oldLine shouldBe null
    }

    it("preserves all fields when present") {
      val position = NotePositionDto(
        positionType = "text",
        newPath = "b.txt",
        oldPath = "a.txt",
        newLine = 10,
        oldLine = 5,
      ).toDomain()

      position shouldBe GitLabNotePosition(
        positionType = "text",
        newPath = "b.txt",
        oldPath = "a.txt",
        newLine = 10,
        oldLine = 5,
      )
    }
  }

  describe("NoteDto.toDomain") {
    fun fullNoteDto(
      id: String? = "note-1",
      createdAt: String? = "2026-01-01T00:00:00Z",
      system: Boolean? = false,
      author: NoteAuthorDto? = NoteAuthorDto("alice"),
      body: String? = "hello",
      userPermissions: NotePermissionsDto? = NotePermissionsDto(true, true, true),
      position: NotePositionDto? = null,
    ) = NoteDto(id, createdAt, system, author, body, userPermissions, position)

    it("defaults id to empty string when null") {
      fullNoteDto(id = null).toDomain().id shouldBe ""
    }

    it("defaults createdAt to empty string when null") {
      fullNoteDto(createdAt = null).toDomain().createdAt shouldBe ""
    }

    it("defaults system to false when null") {
      fullNoteDto(system = null).toDomain().system shouldBe false
    }

    it("defaults body to empty string when null") {
      fullNoteDto(body = null).toDomain().body shouldBe ""
    }

    it("gives authorUsername == \"\" when author is null") {
      fullNoteDto(author = null).toDomain().authorUsername shouldBe ""
    }

    it("gives authorUsername == \"\" when author.username is null") {
      fullNoteDto(author = NoteAuthorDto(null)).toDomain().authorUsername shouldBe ""
    }

    it("gives all three permissions false when userPermissions is null") {
      val permissions = fullNoteDto(userPermissions = null).toDomain().permissions

      permissions shouldBe GitLabNotePermissions(resolveNote = false, adminNote = false, createNote = false)
    }

    it("defaults every permission field to false when the whole set is null") {
      val permissions = fullNoteDto(
        userPermissions = NotePermissionsDto(resolveNote = null, adminNote = null, createNote = null),
      ).toDomain().permissions

      permissions shouldBe GitLabNotePermissions(resolveNote = false, adminNote = false, createNote = false)
    }

    it("a system = true note survives normalization (not filtered here)") {
      fullNoteDto(system = true).toDomain().system shouldBe true
    }

    it("keeps position null when the DTO's position is null") {
      fullNoteDto(position = null).toDomain().position shouldBe null
    }

    it("normalizes position with its fields intact when present") {
      val note = fullNoteDto(
        position = NotePositionDto(
          positionType = "text",
          newPath = "b.txt",
          oldPath = "a.txt",
          newLine = 10,
          oldLine = null,
        ),
      ).toDomain()

      note.position shouldBe GitLabNotePosition(
        positionType = "text",
        newPath = "b.txt",
        oldPath = "a.txt",
        newLine = 10,
        oldLine = null,
      )
    }
  }

  describe("DiscussionDto.toDomain") {
    it("an all-null DiscussionDto normalizes without throwing, giving \"\"/false defaults and an empty notes list") {
      val discussion = DiscussionDto(
        replyId = null,
        createdAt = null,
        resolved = null,
        resolvable = null,
        notes = null,
      ).toDomain()

      discussion shouldBe GitLabDiscussion(
        replyId = "",
        createdAt = "",
        resolved = false,
        resolvable = false,
        notes = emptyList(),
        hasMoreNotes = false,
      )
    }

    it("defaults replyId to empty string when null") {
      DiscussionDto(null, "t", true, true, null).toDomain().replyId shouldBe ""
    }

    it("defaults createdAt to empty string when null") {
      DiscussionDto("id", null, true, true, null).toDomain().createdAt shouldBe ""
    }

    it("defaults resolved to false when null") {
      DiscussionDto("id", "t", null, true, null).toDomain().resolved shouldBe false
    }

    it("defaults resolvable to false when null") {
      DiscussionDto("id", "t", true, null, null).toDomain().resolvable shouldBe false
    }

    fun note(id: String) = NoteDto(id, "t", false, NoteAuthorDto("a"), "body", null, null)

    it("drops null elements inside notes.nodes while keeping the surrounding real notes in order") {
      val discussion = DiscussionDto(
        replyId = "id",
        createdAt = "t",
        resolved = false,
        resolvable = true,
        notes = NoteConnectionDto(pageInfo = null, nodes = listOf(note("1"), null, note("2"))),
      ).toDomain()

      discussion.notes.map { it.id } shouldBe listOf("1", "2")
    }

    it("a null notes list becomes an empty list") {
      val discussion = DiscussionDto(
        replyId = "id",
        createdAt = "t",
        resolved = false,
        resolvable = true,
        notes = NoteConnectionDto(pageInfo = null, nodes = null),
      ).toDomain()

      discussion.notes shouldBe emptyList()
    }

    it("hasMoreNotes is true only when pageInfo.hasNextPage == true") {
      val discussion = DiscussionDto(
        replyId = "id",
        createdAt = "t",
        resolved = false,
        resolvable = true,
        notes = NoteConnectionDto(pageInfo = PageInfoDto(hasNextPage = true, endCursor = "c"), nodes = null),
      ).toDomain()

      discussion.hasMoreNotes shouldBe true
    }

    it("hasMoreNotes is false when hasNextPage = false") {
      val discussion = DiscussionDto(
        replyId = "id",
        createdAt = "t",
        resolved = false,
        resolvable = true,
        notes = NoteConnectionDto(pageInfo = PageInfoDto(hasNextPage = false, endCursor = null), nodes = null),
      ).toDomain()

      discussion.hasMoreNotes shouldBe false
    }

    it("hasMoreNotes is false when pageInfo = null") {
      val discussion = DiscussionDto(
        replyId = "id",
        createdAt = "t",
        resolved = false,
        resolvable = true,
        notes = NoteConnectionDto(pageInfo = null, nodes = null),
      ).toDomain()

      discussion.hasMoreNotes shouldBe false
    }

    it("hasMoreNotes is false when notes = null") {
      val discussion = DiscussionDto(
        replyId = "id",
        createdAt = "t",
        resolved = false,
        resolvable = true,
        notes = null,
      ).toDomain()

      discussion.hasMoreNotes shouldBe false
    }
  }
})
