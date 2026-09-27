package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadEntry
import com.gitlab.eclipse.views.inlinethread.NEW_THREAD_ID
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.ZoneId
import java.time.ZoneOffset

private val REF = MergeRequestRef("https://gitlab.example.com", "fp", 7L, 42L, "gid://gitlab/MergeRequest/1", "g/p")
private val IDENTITY = SessionIdentity("https://gitlab.example.com", "fp", 7L, 42L, "head1", "src/a.kt")

private fun perms(createNote: Boolean = true, resolveNote: Boolean = true) =
  GitLabNotePermissions(resolveNote = resolveNote, adminNote = false, createNote = createNote)

private fun note(
  author: String = "alice",
  body: String = "a comment",
  createdAt: String = "2026-09-27T01:02:03Z",
  permissions: GitLabNotePermissions = perms(),
) = GitLabNote(
  id = "note-$author-${body.hashCode()}",
  createdAt = createdAt,
  system = false,
  authorUsername = author,
  body = body,
  permissions = permissions,
  position = null,
)

private fun thread(
  replyId: String,
  oneBasedLine: Int,
  notes: List<GitLabNote> = listOf(note()),
  resolved: Boolean = false,
  resolvable: Boolean = true,
  hasMoreNotes: Boolean = false,
) = PlacedThread(
  oneBasedLine = oneBasedLine,
  discussion = GitLabDiscussion(
    replyId = replyId,
    createdAt = "2026-09-27T01:02:03Z",
    resolved = resolved,
    resolvable = resolvable,
    notes = notes,
    hasMoreNotes = hasMoreNotes,
  ),
  resolved = resolved,
)

private fun session(placements: List<PlacedThread>, canCreateNote: Boolean = true) = ReviewSessionSnapshot(
  identity = IDENTITY,
  mrRef = REF,
  baseSha = "base1",
  startSha = "start1",
  headSha = "head1",
  oldPath = "src/a.kt",
  newPath = "src/a.kt",
  lineMap = DiffLineMap.Identity,
  canCreateNote = canCreateNote,
  placements = placements,
)

private val UTC: ZoneId = ZoneOffset.UTC

class MrThreadModelMapperTest : DescribeSpec({
  describe("threadsAt") {
    it("maps the threads of the line, in placement order, keyed by reply id and titled 'Thread i of N'") {
      val s = session(listOf(thread("d1", 10), thread("other", 11), thread("d2", 10)))

      val model = MrThreadModelMapper.threadsAt(s, 10, UTC).shouldNotBeNull()

      model.items.map { it.threadId } shouldContainExactly listOf("d1", "d2")
      model.items.map { it.title } shouldContainExactly listOf("Thread 1 of 2", "Thread 2 of 2")
    }

    it("works for the first line (oneBasedLine = 1)") {
      val model = MrThreadModelMapper.threadsAt(session(listOf(thread("d1", 1))), 1, UTC).shouldNotBeNull()
      model.items.single().threadId shouldBe "d1"
    }

    it("returns null when the line has no placed thread") {
      MrThreadModelMapper.threadsAt(session(listOf(thread("d1", 10))), 11, UTC).shouldBeNull()
      MrThreadModelMapper.threadsAt(session(emptyList()), 1, UTC).shouldBeNull()
    }

    it("renders every fetched note as author, formatted date and the Markdown source verbatim") {
      val notes = listOf(
        note("alice", "first **line**\n\n```kotlin\nval x = 1\n```", "2026-09-27T01:02:03Z"),
        note("bob", "  indented reply <b>&</b>  ", "2026-12-31T23:59:00Z"),
      )
      val model = MrThreadModelMapper.threadsAt(session(listOf(thread("d1", 5, notes))), 5, UTC).shouldNotBeNull()

      model.items.single().entries shouldContainExactly listOf(
        InlineThreadEntry("alice", "2026-09-27 01:02", "first **line**\n\n```kotlin\nval x = 1\n```"),
        InlineThreadEntry("bob", "2026-12-31 23:59", "  indented reply <b>&</b>  "),
      )
    }

    it("flags a partially fetched thread through moreEntriesOnServer (A19)") {
      val s = session(listOf(thread("d1", 3, hasMoreNotes = true), thread("d2", 3, hasMoreNotes = false)))
      val model = MrThreadModelMapper.threadsAt(s, 3, UTC).shouldNotBeNull()
      model.items.map { it.moreEntriesOnServer } shouldContainExactly listOf(true, false)
    }

    it("exposes the resolution state only for resolvable threads") {
      val s = session(
        listOf(
          thread("d1", 3, resolved = true, resolvable = true),
          thread("d2", 3, resolved = false, resolvable = true),
          thread("d3", 3, resolved = false, resolvable = false),
        ),
      )
      val model = MrThreadModelMapper.threadsAt(s, 3, UTC).shouldNotBeNull()
      model.items.map { it.resolved } shouldContainExactly listOf(true, false, null)
    }

    describe("actions follow the permissions (FR-6, FR-9)") {
      fun actions(
        createNote: Boolean = true,
        resolveNote: Boolean = true,
        resolvable: Boolean = true,
        resolved: Boolean = false,
        sessionCanCreateNote: Boolean = true,
      ): Set<InlineThreadAction> {
        val t = thread(
          "d1",
          3,
          notes = listOf(
            note(permissions = perms(createNote, resolveNote)),
            note("bob", "reply", permissions = perms(false, false)),
          ),
          resolved = resolved,
          resolvable = resolvable,
        )
        val model = MrThreadModelMapper.threadsAt(session(listOf(t), sessionCanCreateNote), 3, UTC).shouldNotBeNull()
        return model.items.single().actions
      }

      it("offers REPLY and RESOLVE for an unresolved thread the user may reply to and resolve") {
        actions() shouldBe setOf(InlineThreadAction.REPLY, InlineThreadAction.RESOLVE)
      }

      it("offers UNRESOLVE instead of RESOLVE for a resolved thread") {
        actions(resolved = true) shouldBe setOf(InlineThreadAction.REPLY, InlineThreadAction.UNRESOLVE)
      }

      it("withholds REPLY when the first note's createNote is false") {
        actions(createNote = false) shouldBe setOf(InlineThreadAction.RESOLVE)
      }

      it("withholds REPLY when the session's MR-level createNote is false, whatever the note says (FR-9)") {
        actions(sessionCanCreateNote = false) shouldBe setOf(InlineThreadAction.RESOLVE)
      }

      it("withholds RESOLVE / UNRESOLVE without resolveNote") {
        actions(resolveNote = false) shouldBe setOf(InlineThreadAction.REPLY)
        actions(resolveNote = false, resolved = true) shouldBe setOf(InlineThreadAction.REPLY)
      }

      it("withholds RESOLVE / UNRESOLVE for a non-resolvable thread") {
        actions(resolvable = false) shouldBe setOf(InlineThreadAction.REPLY)
        actions(resolvable = false, resolved = true) shouldBe setOf(InlineThreadAction.REPLY)
      }

      it("looks at the first note's permissions, not a reply's") {
        val first = note(permissions = perms(false, false))
        val reply = note("bob", "r", permissions = perms(true, true))
        val t = thread("d1", 3, notes = listOf(first, reply))
        val model = MrThreadModelMapper.threadsAt(session(listOf(t)), 3, UTC).shouldNotBeNull()
        model.items.single().actions.shouldBeEmpty()
      }
    }

    it("shows an input field only when a reply is possible") {
      val yes = thread("d1", 3)
      val no = thread("d2", 3, notes = listOf(note(permissions = perms(createNote = false))))
      val model = MrThreadModelMapper.threadsAt(session(listOf(yes, no)), 3, UTC).shouldNotBeNull()
      model.items[0].inputPlaceholder.shouldNotBeNull()
      model.items[1].inputPlaceholder.shouldBeNull()
    }

    it("skips a thread without notes rather than failing") {
      val s = session(listOf(thread("empty", 3, notes = emptyList()), thread("d1", 3)))
      val model = MrThreadModelMapper.threadsAt(s, 3, UTC).shouldNotBeNull()
      model.items.map { it.threadId } shouldContainExactly listOf("d1")
    }
  }

  describe("threadsWithIds (the live annotation's threads, design §9.2)") {
    it("returns the threads with the given reply ids, whatever line they were loaded on") {
      // "moved" was loaded on line 3 and its annotation moved to line 5 with an edit; "stay" was
      // loaded on line 5. The ids of the annotation now on line 5 are what decides.
      val s = session(listOf(thread("moved", 3), thread("stay", 5)))

      val model = MrThreadModelMapper.threadsWithIds(s, listOf("moved"), UTC).shouldNotBeNull()

      model.items.map { it.threadId } shouldContainExactly listOf("moved")
      model.items.single().title shouldBe "Thread 1 of 1"
    }

    it("keeps the placement order and ignores duplicate ids") {
      val s = session(listOf(thread("d1", 3), thread("d2", 9), thread("d3", 3)))

      val model = MrThreadModelMapper.threadsWithIds(s, listOf("d3", "d1", "d3"), UTC).shouldNotBeNull()

      model.items.map { it.threadId to it.title } shouldContainExactly listOf(
        "d1" to "Thread 1 of 2",
        "d3" to "Thread 2 of 2",
      )
    }

    it("is null for no ids, for ids the snapshot does not have, and for a thread without notes") {
      val s = session(listOf(thread("d1", 3), thread("empty", 4, notes = emptyList())))

      MrThreadModelMapper.threadsWithIds(s, emptyList(), UTC).shouldBeNull()
      MrThreadModelMapper.threadsWithIds(s, listOf("gone"), UTC).shouldBeNull()
      MrThreadModelMapper.threadsWithIds(s, listOf("empty"), UTC).shouldBeNull()
    }
  }

  describe("newThread") {
    it("builds the single CREATE item with no entries and a placeholder naming the line") {
      val model = MrThreadModelMapper.newThread(12, session = null).shouldNotBeNull()
      val item = model.items.single()
      item.threadId shouldBe NEW_THREAD_ID
      item.entries.shouldBeEmpty()
      item.resolved.shouldBeNull()
      item.moreEntriesOnServer shouldBe false
      item.actions shouldBe setOf(InlineThreadAction.CREATE)
      item.inputPlaceholder.shouldNotBeNull() shouldContain "12"
      item.title shouldContain "12"
    }

    it("is allowed for the first line and for a session that permits notes") {
      MrThreadModelMapper.newThread(1, session(emptyList(), canCreateNote = true)).shouldNotBeNull()
    }

    it("is refused for a session whose MR-level createNote is false (FR-9)") {
      MrThreadModelMapper.newThread(12, session(emptyList(), canCreateNote = false)).shouldBeNull()
    }
  }

  describe("formatCreatedAt") {
    it("formats an ISO instant in the given zone as yyyy-MM-dd HH:mm") {
      MrThreadModelMapper.formatCreatedAt("2026-09-27T01:02:03Z", UTC) shouldBe "2026-09-27 01:02"
      MrThreadModelMapper.formatCreatedAt("2026-09-27T01:02:03Z", ZoneOffset.ofHours(9)) shouldBe "2026-09-27 10:02"
    }

    it("accepts an offset timestamp") {
      MrThreadModelMapper.formatCreatedAt("2026-09-27T10:02:03+09:00", UTC) shouldBe "2026-09-27 01:02"
    }

    it("falls back to the raw text when it cannot be parsed") {
      MrThreadModelMapper.formatCreatedAt("yesterday", UTC) shouldBe "yesterday"
      MrThreadModelMapper.formatCreatedAt("", UTC) shouldBe ""
    }
  }
})
