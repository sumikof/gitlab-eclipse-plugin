package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.views.inlinethread.LineAnnotation
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private val PERMS = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true)

private fun note(author: String = "alice", body: String = "a comment") = GitLabNote(
  id = "note-$author-${body.hashCode()}",
  createdAt = "2026-01-01T00:00:00Z",
  system = false,
  authorUsername = author,
  body = body,
  permissions = PERMS,
  position = null,
)

private fun thread(
  body: String = "a comment",
  author: String = "alice",
  replies: Int = 0,
  resolved: Boolean = false,
  hasMoreNotes: Boolean = false,
  oneBasedLine: Int = 10,
) = PlacedThread(
  oneBasedLine = oneBasedLine,
  discussion = GitLabDiscussion(
    replyId = "disc-1",
    createdAt = "2026-01-01T00:00:00Z",
    resolved = resolved,
    resolvable = true,
    notes = listOf(note(author, body)) + List(replies) { note("bob", "reply $it") },
    hasMoreNotes = hasMoreNotes,
  ),
  resolved = resolved,
)

class ThreadHoverTextTest : DescribeSpec({
  describe("ThreadHoverText.of") {
    it("shows the author, the body, the reply count and the resolution state (FR-4)") {
      ThreadHoverText.of(thread(body = "Looks good", author = "alice", replies = 2)) shouldBe
        "alice: Looks good — 2 replies, unresolved"
    }

    it("escapes < and & (and > and \") in the body because the ruler hover renders HTML (E5)") {
      val text = ThreadHoverText.of(thread(body = "if (a < b && c > \"d\")"))

      text shouldContain "&lt;"
      text shouldContain "&amp;&amp;"
      text shouldContain "&gt;"
      text shouldContain "&quot;"
      text shouldNotContain "<"
      text shouldNotContain " & "
      text shouldNotContain "\""
    }

    it("escapes the author too") {
      ThreadHoverText.of(thread(author = "<script>")) shouldStartWith "&lt;script&gt;:"
    }

    it("does not escape twice: a literal &amp; in the source becomes &amp;amp;") {
      ThreadHoverText.of(thread(body = "&amp;")) shouldContain "&amp;amp;"
    }

    it("keeps only the first line of the body, whatever the line ending") {
      ThreadHoverText.of(thread(body = "first\nsecond")) shouldBe "alice: first — no replies, unresolved"
      ThreadHoverText.of(thread(body = "first\r\nsecond")) shouldBe "alice: first — no replies, unresolved"
      ThreadHoverText.of(thread(body = "first\rsecond")) shouldBe "alice: first — no replies, unresolved"
    }

    it("skips leading blank lines and trims the first line") {
      ThreadHoverText.of(thread(body = "\n\n  padded  \nmore")) shouldBe "alice: padded — no replies, unresolved"
    }

    it("shows a placeholder for an empty body") {
      ThreadHoverText.of(thread(body = "")) shouldBe "alice: (no text) — no replies, unresolved"
      ThreadHoverText.of(thread(body = "   \n  ")) shouldBe "alice: (no text) — no replies, unresolved"
    }

    it("keeps a first line of exactly 200 characters intact") {
      val line = "x".repeat(ThreadHoverText.MAX_BODY_CHARS)

      ThreadHoverText.of(thread(body = line)) shouldBe "alice: $line — no replies, unresolved"
    }

    it("cuts a first line longer than 200 characters to 200 and marks the cut") {
      val line = "y".repeat(ThreadHoverText.MAX_BODY_CHARS + 1)

      val text = ThreadHoverText.of(thread(body = line))

      text shouldBe "alice: ${"y".repeat(ThreadHoverText.MAX_BODY_CHARS)}… — no replies, unresolved"
    }

    it("counts the 200 characters before escaping, so escaping never eats the budget") {
      val line = "&".repeat(ThreadHoverText.MAX_BODY_CHARS)

      val text = ThreadHoverText.of(thread(body = line))

      text shouldStartWith "alice: " + "&amp;".repeat(ThreadHoverText.MAX_BODY_CHARS) + " — "
      text shouldNotContain "…"
    }

    it("counts replies as the notes after the first one, singular and plural") {
      ThreadHoverText.of(thread(replies = 0)) shouldEndWith "no replies, unresolved"
      ThreadHoverText.of(thread(replies = 1)) shouldEndWith "1 reply, unresolved"
      ThreadHoverText.of(thread(replies = 3)) shouldEndWith "3 replies, unresolved"
    }

    it("marks the count as a lower bound when the server holds more notes (§9.1.1)") {
      ThreadHoverText.of(thread(replies = 2, hasMoreNotes = true)) shouldEndWith "2+ replies, unresolved"
      ThreadHoverText.of(thread(replies = 0, hasMoreNotes = true)) shouldEndWith "0+ replies, unresolved"
    }

    it("says resolved for a resolved thread") {
      ThreadHoverText.of(thread(resolved = true, replies = 1)) shouldEndWith "1 reply, resolved"
    }

    it("names an author-less note") {
      ThreadHoverText.of(thread(author = "")) shouldStartWith "unknown: "
    }
  }

  describe("toLineAnnotation") {
    it("maps an unresolved thread to the unresolved annotation type on its line, including line 1") {
      thread(oneBasedLine = 1, body = "top").toLineAnnotation() shouldBe
        LineAnnotation(1, UNRESOLVED_THREAD_ANNOTATION_TYPE, "alice: top — no replies, unresolved")
    }

    it("maps a resolved thread to the resolved annotation type") {
      thread(oneBasedLine = 7, resolved = true).toLineAnnotation().type shouldBe RESOLVED_THREAD_ANNOTATION_TYPE
    }

    it("keeps the order of the placements") {
      val annotations = lineAnnotationsOf(listOf(thread(oneBasedLine = 3), thread(oneBasedLine = 1, resolved = true)))

      annotations.map { it.oneBasedLine to it.type } shouldContainExactly listOf(
        3 to UNRESOLVED_THREAD_ANNOTATION_TYPE,
        1 to RESOLVED_THREAD_ANNOTATION_TYPE,
      )
    }
  }
})
