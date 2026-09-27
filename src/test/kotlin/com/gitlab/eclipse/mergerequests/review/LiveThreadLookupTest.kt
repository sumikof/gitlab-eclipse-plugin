package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.views.inlinethread.ThreadAnnotationAttacher
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.source.AnnotationModel
import java.time.ZoneOffset

private fun placed(replyId: String, oneBasedLine: Int) = PlacedThread(
  oneBasedLine = oneBasedLine,
  discussion = GitLabDiscussion(
    replyId = replyId,
    createdAt = "2026-09-27T01:02:03Z",
    resolved = false,
    resolvable = true,
    notes = listOf(
      GitLabNote(
        id = "note-$replyId",
        createdAt = "2026-09-27T01:02:03Z",
        system = false,
        authorUsername = "alice",
        body = "on $replyId",
        permissions = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true),
        position = null,
      ),
    ),
    hasMoreNotes = false,
  ),
  resolved = false,
)

private fun snapshotOf(placements: List<PlacedThread>) = ReviewSessionSnapshot(
  identity = SessionIdentity("https://gitlab.example.com", "fp", 7L, 42L, "head1", "src/a.kt"),
  mrRef = MergeRequestRef("https://gitlab.example.com", "fp", 7L, 42L, "gid://gitlab/MergeRequest/1", "g/p"),
  baseSha = "base1",
  startSha = "start1",
  headSha = "head1",
  oldPath = "src/a.kt",
  newPath = "src/a.kt",
  lineMap = DiffLineMap.Identity,
  canCreateNote = true,
  placements = placements,
)

/**
 * The popup's "threads of the clicked live line" chain, SWT-free (design §9.2, E4): the snapshot's
 * annotations are attached to a connected document, the document is edited, and the clicked line's
 * live annotation ids pick the threads — never the loaded placement line.
 */
class LiveThreadLookupTest : DescribeSpec({
  describe("the threads of a clicked live line") {
    val zone = ZoneOffset.UTC

    it("opens the moved thread, not the thread originally loaded on the clicked line") {
      val document = Document((1..10).joinToString("\n") { "line $it" })
      val parent = AnnotationModel().also { it.connect(document) }
      val attacher = ThreadAnnotationAttacher()
      val snapshot = snapshotOf(listOf(placed("moved", 3), placed("stay", 5)))
      attacher.replace(document, parent, lineAnnotationsOf(snapshot.placements)) shouldBe true

      document.replace(0, 0, "inserted 1\ninserted 2\n") // "moved" now sits on line 5, "stay" on 7

      val onFive = MrThreadModelMapper.threadsWithIds(snapshot, attacher.threadIdsAt(document, 5), zone)
      onFive.shouldNotBeNull().items.map { it.threadId } shouldContainExactly listOf("moved")
      val onSeven = MrThreadModelMapper.threadsWithIds(snapshot, attacher.threadIdsAt(document, 7), zone)
      onSeven.shouldNotBeNull().items.map { it.threadId } shouldContainExactly listOf("stay")
      // The moved thread's loaded line is empty now.
      MrThreadModelMapper.threadsWithIds(snapshot, attacher.threadIdsAt(document, 3), zone).shouldBeNull()
    }
  }
})
