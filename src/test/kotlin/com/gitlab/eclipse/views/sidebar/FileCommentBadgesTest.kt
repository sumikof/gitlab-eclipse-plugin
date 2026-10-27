package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.TruncationReason
import com.gitlab.eclipse.api.model.GitLabDiffRefs
import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val BASE = "base-sha"
private const val HEAD = "head-sha"
private const val START = "start-sha"

private val currentRefs = GitLabDiffRefs(baseSha = BASE, headSha = HEAD, startSha = START)

private fun pos(
  oldPath: String? = null,
  newPath: String? = null,
  oldLine: Int? = null,
  newLine: Int? = null,
  type: String = "text",
  refs: GitLabDiffRefs? = currentRefs,
) = GitLabNotePosition(type, newPath, oldPath, newLine, oldLine, refs)

private fun note(position: GitLabNotePosition?, system: Boolean = false) = GitLabNote(
  id = "n",
  createdAt = "2024-01-01T00:00:00Z",
  system = system,
  authorUsername = "alice",
  body = "b",
  permissions = GitLabNotePermissions(resolveNote = false, adminNote = false, createNote = false),
  position = position,
)

private fun thread(vararg notes: GitLabNote, resolved: Boolean = false) =
  GitLabDiscussion("r", "2024-01-01T00:00:00Z", resolved, resolvable = true, notes = notes.toList(), hasMoreNotes = false)

private fun sidesOf(vararg threads: GitLabDiscussion) = commentedSides(threads.toList(), BASE, HEAD)

private fun complete(vararg threads: GitLabDiscussion) = CommentIndex.Complete(sidesOf(*threads))

private fun state(index: CommentIndex, type: ChangeType, oldPath: String? = "a.kt", newPath: String? = "a.kt") =
  fileCommentState(oldPath, newPath, type, index)

class FileCommentBadgesTest : StringSpec({
  // §4.6 #1, #2
  "a thread on a new-side line marks the modified file, resolved or not" {
    val unresolved = complete(thread(note(pos(newPath = "a.kt", newLine = 3))))
    val resolved = complete(thread(note(pos(newPath = "a.kt", newLine = 3)), resolved = true))
    state(unresolved, ChangeType.MODIFIED) shouldBe FileCommentState.PRESENT
    state(resolved, ChangeType.MODIFIED) shouldBe FileCommentState.PRESENT
  }

  // §4.6 #3: an unchanged line carries both lines and is matched on the old side.
  "an unchanged-line thread is matched on the old side with the base sha" {
    sidesOf(thread(note(pos(oldPath = "a.kt", newPath = "a.kt", oldLine = 2, newLine = 2)))) shouldBe
      CommentedSides(oldPaths = setOf("a.kt"), newPaths = emptySet())
    val wrongBase = currentRefs.copy(baseSha = "other")
    sidesOf(thread(note(pos(oldPath = "a.kt", newPath = "a.kt", oldLine = 2, newLine = 2, refs = wrongBase)))) shouldBe
      CommentedSides(emptySet(), emptySet())
  }

  // §4.6 #4
  "an old-side thread marks a deleted file" {
    val index = complete(thread(note(pos(oldPath = "gone.kt", oldLine = 1))))
    state(index, ChangeType.DELETED, oldPath = "gone.kt", newPath = "gone.kt") shouldBe FileCommentState.PRESENT
  }

  // §4.6 #5, #6
  "a renamed file is matched by its new path on the new side and its old path on the old side" {
    val newSide = complete(thread(note(pos(newPath = "new.kt", newLine = 1))))
    val oldSide = complete(thread(note(pos(oldPath = "old.kt", oldLine = 1))))
    state(newSide, ChangeType.RENAMED, oldPath = "old.kt", newPath = "new.kt") shouldBe FileCommentState.PRESENT
    state(oldSide, ChangeType.RENAMED, oldPath = "old.kt", newPath = "new.kt") shouldBe FileCommentState.PRESENT
    val crossed = complete(thread(note(pos(newPath = "old.kt", newLine = 1))))
    state(crossed, ChangeType.RENAMED, oldPath = "old.kt", newPath = "new.kt") shouldBe FileCommentState.ABSENT
  }

  // §4.6 #7: the exists condition (no base side for a new file, no head side for a deleted one).
  "an added file never matches on the old side and a deleted file never on the new side" {
    val oldSide = complete(thread(note(pos(oldPath = "a.kt", oldLine = 1))))
    val newSide = complete(thread(note(pos(newPath = "a.kt", newLine = 1))))
    state(oldSide, ChangeType.NEW) shouldBe FileCommentState.ABSENT
    state(newSide, ChangeType.DELETED) shouldBe FileCommentState.ABSENT
    state(newSide, ChangeType.NEW) shouldBe FileCommentState.PRESENT
  }

  // §4.6 #8, #10
  "threads without a position or with a non-text position are not counted" {
    sidesOf(thread(note(null))) shouldBe CommentedSides(emptySet(), emptySet())
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1, type = "image")))) shouldBe
      CommentedSides(emptySet(), emptySet())
    state(complete(thread(note(null))), ChangeType.MODIFIED) shouldBe FileCommentState.ABSENT
  }

  "a thread whose position has no diff refs is not counted" {
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1, refs = null)))) shouldBe CommentedSides(emptySet(), emptySet())
  }

  // §4.6 #11, and old ↔ base / new ↔ head are never crossed.
  "the old side is checked against the base sha and the new side against the head sha" {
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1, refs = currentRefs.copy(headSha = "older"))))) shouldBe
      CommentedSides(emptySet(), emptySet())
    sidesOf(thread(note(pos(oldPath = "a.kt", oldLine = 1, refs = currentRefs.copy(baseSha = "older"))))) shouldBe
      CommentedSides(emptySet(), emptySet())
    // Swapped refs: a new-side note whose head is our base, an old-side note whose base is our head.
    val swapped = GitLabDiffRefs(baseSha = HEAD, headSha = BASE, startSha = START)
    sidesOf(
      thread(note(pos(newPath = "a.kt", newLine = 1, refs = swapped))),
      thread(note(pos(oldPath = "b.kt", oldLine = 1, refs = swapped))),
    ) shouldBe CommentedSides(emptySet(), emptySet())
    // startSha is not part of the match (VS Code parity).
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1, refs = currentRefs.copy(startSha = "x"))))) shouldBe
      CommentedSides(emptySet(), setOf("a.kt"))
  }

  "sha comparison is case-sensitive" {
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1, refs = currentRefs.copy(headSha = HEAD.uppercase()))))) shouldBe
      CommentedSides(emptySet(), emptySet())
  }

  "only the first note's position is looked at, never a reply's" {
    sidesOf(thread(note(null), note(pos(newPath = "a.kt", newLine = 1)))) shouldBe CommentedSides(emptySet(), emptySet())
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1)), note(pos(newPath = "b.kt", newLine = 1)))) shouldBe
      CommentedSides(emptySet(), setOf("a.kt"))
  }

  // I-6: the fetch drops system notes, so the first remaining note decides.
  "a leading system note is skipped and the first non-system note decides" {
    sidesOf(thread(note(null, system = true), note(pos(newPath = "a.kt", newLine = 1)))) shouldBe
      CommentedSides(emptySet(), setOf("a.kt"))
    sidesOf(thread(note(pos(newPath = "a.kt", newLine = 1), system = true))) shouldBe
      CommentedSides(emptySet(), emptySet())
  }

  // I-7
  "a side whose path is null in the position does not match anything" {
    sidesOf(thread(note(pos(oldPath = null, newPath = "a.kt", oldLine = 1)))) shouldBe CommentedSides(emptySet(), emptySet())
    sidesOf(thread(note(pos(oldPath = "a.kt", newPath = null, newLine = 1)))) shouldBe CommentedSides(emptySet(), emptySet())
  }

  "a file path that is null on the node never matches" {
    val index = complete(thread(note(pos(oldPath = "a.kt", oldLine = 1))), thread(note(pos(newPath = "a.kt", newLine = 1))))
    state(index, ChangeType.MODIFIED, oldPath = null, newPath = null) shouldBe FileCommentState.ABSENT
  }

  // §4.6 #12
  "a truncated fetch is partial: found files are present, the rest unknown" {
    val result = DiscussionsReadResult(
      canCreateNote = true,
      discussions = listOf(thread(note(pos(newPath = "x.kt", newLine = 1)))),
      truncation = TruncationReason.PAGE_LIMIT,
    )
    val index = commentIndexOf(result, BASE, HEAD)
    index shouldBe CommentIndex.Partial(CommentedSides(emptySet(), setOf("x.kt")))
    state(index, ChangeType.MODIFIED, "x.kt", "x.kt") shouldBe FileCommentState.PRESENT
    state(index, ChangeType.MODIFIED, "y.kt", "y.kt") shouldBe FileCommentState.UNKNOWN
  }

  "every truncation reason is partial" {
    TruncationReason.entries.forEach { reason ->
      commentIndexOf(DiscussionsReadResult(true, emptyList(), reason), BASE, HEAD) shouldBe
        CommentIndex.Partial(CommentedSides(emptySet(), emptySet()))
    }
  }

  "a complete fetch is complete: unmatched files are absent" {
    val result = DiscussionsReadResult(true, listOf(thread(note(pos(newPath = "x.kt", newLine = 1)))), null)
    val index = commentIndexOf(result, BASE, HEAD)
    index shouldBe CommentIndex.Complete(CommentedSides(emptySet(), setOf("x.kt")))
    state(index, ChangeType.MODIFIED, "y.kt", "y.kt") shouldBe FileCommentState.ABSENT
  }

  // §4.6 #13
  "a failed or gate-rejected fetch is unavailable and every file unknown" {
    val index = commentIndexOf(null, BASE, HEAD)
    index shouldBe CommentIndex.Unavailable
    state(index, ChangeType.MODIFIED) shouldBe FileCommentState.UNKNOWN
  }

  "an unknown version sha makes the index unavailable, never complete" {
    val result = DiscussionsReadResult(true, emptyList(), null)
    commentIndexOf(result, null, HEAD) shouldBe CommentIndex.Unavailable
    commentIndexOf(result, BASE, null) shouldBe CommentIndex.Unavailable
  }

  "pending is unknown" {
    state(CommentIndex.Pending, ChangeType.MODIFIED) shouldBe FileCommentState.UNKNOWN
  }

  "a fresh holder carries the version shas and starts pending" {
    val badges = MrCommentBadges(BASE, HEAD)
    badges.baseSha shouldBe BASE
    badges.headSha shouldBe HEAD
    badges.index shouldBe CommentIndex.Pending
  }
})
