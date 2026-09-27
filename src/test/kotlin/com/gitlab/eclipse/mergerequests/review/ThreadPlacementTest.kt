package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabDiffRefs
import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private val PERMS = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true)
private val REFS = VersionRefs(baseSha = "base1", startSha = "start1", headSha = "head1")
private const val NEW_PATH = "src/Foo.kt"

private fun position(
  positionType: String? = "text",
  newPath: String? = NEW_PATH,
  oldPath: String? = NEW_PATH,
  newLine: Int? = 10,
  oldLine: Int? = null,
  diffRefs: GitLabDiffRefs? = GitLabDiffRefs(baseSha = "base1", headSha = "head1", startSha = "start1"),
) = GitLabNotePosition(
  positionType = positionType ?: "text",
  newPath = newPath,
  oldPath = oldPath,
  newLine = newLine,
  oldLine = oldLine,
  diffRefs = diffRefs,
)

private fun note(id: String = "note-1", position: GitLabNotePosition? = position()) = GitLabNote(
  id = id,
  createdAt = "2026-01-01T00:00:00Z",
  system = false,
  authorUsername = "alice",
  body = "a comment",
  permissions = PERMS,
  position = position,
)

private fun discussion(
  replyId: String = "disc-1",
  resolved: Boolean = false,
  notes: List<GitLabNote> = listOf(note()),
) = GitLabDiscussion(
  replyId = replyId,
  createdAt = "2026-01-01T00:00:00Z",
  resolved = resolved,
  resolvable = true,
  notes = notes,
  hasMoreNotes = false,
)

class ThreadPlacementTest : DescribeSpec({
  describe("placeThreads") {
    it("places a discussion whose first note's position matches everything (FR-3)") {
      val d = discussion()

      val result = placeThreads(listOf(d), REFS, NEW_PATH)

      result shouldBe listOf(PlacedThread(oneBasedLine = 10, discussion = d, resolved = false))
    }

    it("skips a discussion whose first note's diffRefs.baseSha does not match the session") {
      val mismatchedRefs = GitLabDiffRefs(baseSha = "other", headSha = "head1", startSha = "start1")
      val d = discussion(notes = listOf(note(position = position(diffRefs = mismatchedRefs))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note's diffRefs.startSha does not match the session") {
      val mismatchedRefs = GitLabDiffRefs(baseSha = "base1", headSha = "head1", startSha = "other")
      val d = discussion(notes = listOf(note(position = position(diffRefs = mismatchedRefs))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note's diffRefs.headSha does not match the session") {
      val mismatchedRefs = GitLabDiffRefs(baseSha = "base1", headSha = "other", startSha = "start1")
      val d = discussion(notes = listOf(note(position = position(diffRefs = mismatchedRefs))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note's position has diffRefs == null") {
      val d = discussion(notes = listOf(note(position = position(diffRefs = null))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note's positionType is \"image\"") {
      val d = discussion(notes = listOf(note(position = position(positionType = "image"))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note's position has only oldLine (newLine is null)") {
      val d = discussion(notes = listOf(note(position = position(newLine = null, oldLine = 5))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note's newPath does not match the editor's file") {
      val d = discussion(notes = listOf(note(position = position(newPath = "src/Other.kt"))))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("places a resolved discussion with resolved = true") {
      val d = discussion(resolved = true)

      val result = placeThreads(listOf(d), REFS, NEW_PATH)

      result shouldBe listOf(PlacedThread(oneBasedLine = 10, discussion = d, resolved = true))
    }

    it("skips a discussion with no notes") {
      val d = discussion(notes = emptyList())

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("skips a discussion whose first note has no position (a plain discussion note)") {
      val d = discussion(notes = listOf(note(position = null)))

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("judges only the first note's position: a matching reply does not rescue a non-matching root note") {
      val d = discussion(
        notes = listOf(
          note(id = "root", position = position(newPath = "src/Other.kt")),
          note(id = "reply", position = position()),
        ),
      )

      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe emptyList()
    }

    it("judges only the first note's position: a non-matching reply does not exclude a matching root note") {
      val d = discussion(
        notes = listOf(
          note(id = "root", position = position()),
          note(id = "reply", position = position(newPath = "src/Other.kt")),
        ),
      )

      val expected = listOf(PlacedThread(oneBasedLine = 10, discussion = d, resolved = false))
      placeThreads(listOf(d), REFS, NEW_PATH) shouldBe expected
    }
  }
})
