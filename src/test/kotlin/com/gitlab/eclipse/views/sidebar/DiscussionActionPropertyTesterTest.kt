package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabNotePermissions
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp-0"
private const val PROJECT_ID = 7L
private const val MR_IID = 42L
private const val MR_GID = "gid://gitlab/MergeRequest/99"
private const val MR_SHA = "abc123"
private const val NAMESPACE = "group/project"
private const val REPLY_ID = "gid://gitlab/Discussion/dis-1"
private const val NOTE_GID = "gid://gitlab/Note/123"

/** Every property the tester answers, so the "unknown receiver / null receiver" cases can loop. */
private val ALL_PROPERTIES = listOf(
  "canReplyToThread",
  "canResolveThread",
  "canUnresolveThread",
  "canEditNote",
  "canDeleteNote",
  "canCommentOnMergeRequest",
)

private fun perms(
  resolveNote: Boolean = false,
  adminNote: Boolean = false,
  createNote: Boolean = false,
) = GitLabNotePermissions(resolveNote = resolveNote, adminNote = adminNote, createNote = createNote)

private fun threadNode(
  resolved: Boolean = false,
  resolvable: Boolean = true,
  permissions: GitLabNotePermissions = perms(),
) = ThreadNode(
  sourceInstanceUrl = SRC_URL,
  sourceAuthFingerprint = SRC_FP,
  projectId = PROJECT_ID,
  mrIid = MR_IID,
  mrGid = MR_GID,
  mrSha = MR_SHA,
  namespaceWithPath = NAMESPACE,
  replyId = REPLY_ID,
  resolved = resolved,
  resolvable = resolvable,
  position = null,
  permissions = permissions,
  children = emptyList(),
)

private fun noteNode(permissions: GitLabNotePermissions = perms()) = NoteNode(
  sourceInstanceUrl = SRC_URL,
  sourceAuthFingerprint = SRC_FP,
  projectId = PROJECT_ID,
  mrIid = MR_IID,
  mrGid = MR_GID,
  mrSha = MR_SHA,
  namespaceWithPath = NAMESPACE,
  noteGid = NOTE_GID,
  replyId = REPLY_ID,
  body = "a comment",
  authorUsername = "alice",
  createdAt = "2026-01-01T00:00:00Z",
  permissions = permissions,
)

private fun sectionNode(canCreateNote: Boolean) = DiscussionsSectionNode(
  sourceInstanceUrl = SRC_URL,
  sourceAuthFingerprint = SRC_FP,
  projectId = PROJECT_ID,
  mrIid = MR_IID,
  mrGid = MR_GID,
  mrSha = MR_SHA,
  namespaceWithPath = NAMESPACE,
).apply { this.canCreateNote = canCreateNote }

/**
 * FR-9: never offer an action the account cannot perform. The last block is the load-bearing one —
 * it proves the menu's `visibleWhen` (which goes through [DiscussionActionPropertyTester]) and the
 * handlers' runtime re-check (which calls the exported predicates directly) can never disagree.
 */
class DiscussionActionPropertyTesterTest : DescribeSpec({
  val tester = DiscussionActionPropertyTester()

  fun test(receiver: Any?, property: String?) = tester.test(receiver, property, emptyArray<Any?>(), null)

  describe("the six properties on their positive cases") {
    it("canReplyToThread is true when the thread's permissions allow creating a note") {
      test(threadNode(permissions = perms(createNote = true)), "canReplyToThread") shouldBe true
    }

    it("canResolveThread is true for a resolvable, unresolved thread the user may resolve") {
      val node = threadNode(resolved = false, resolvable = true, permissions = perms(resolveNote = true))
      test(node, "canResolveThread") shouldBe true
    }

    it("canUnresolveThread is true for a resolvable, resolved thread the user may resolve") {
      val node = threadNode(resolved = true, resolvable = true, permissions = perms(resolveNote = true))
      test(node, "canUnresolveThread") shouldBe true
    }

    it("canEditNote is true when the note's permissions allow administering it") {
      test(noteNode(perms(adminNote = true)), "canEditNote") shouldBe true
    }

    it("canDeleteNote is true when the note's permissions allow administering it") {
      test(noteNode(perms(adminNote = true)), "canDeleteNote") shouldBe true
    }

    it("canCommentOnMergeRequest is true when the section reports canCreateNote") {
      test(sectionNode(canCreateNote = true), "canCommentOnMergeRequest") shouldBe true
    }
  }

  describe("canResolveThread's three negative conditions") {
    it("is false when the thread is not resolvable, even though it is unresolved and permitted") {
      val node = threadNode(resolved = false, resolvable = false, permissions = perms(resolveNote = true))
      test(node, "canResolveThread") shouldBe false
    }

    it("is false when the thread is already resolved") {
      val node = threadNode(resolved = true, resolvable = true, permissions = perms(resolveNote = true))
      test(node, "canResolveThread") shouldBe false
    }

    it("is false when the account may not resolve notes") {
      val node = threadNode(resolved = false, resolvable = true, permissions = perms(resolveNote = false))
      test(node, "canResolveThread") shouldBe false
    }
  }

  describe("canUnresolveThread's three negative conditions") {
    it("is false when the thread is not resolvable, even though it is resolved and permitted") {
      val node = threadNode(resolved = true, resolvable = false, permissions = perms(resolveNote = true))
      test(node, "canUnresolveThread") shouldBe false
    }

    it("is false when the thread is not resolved") {
      val node = threadNode(resolved = false, resolvable = true, permissions = perms(resolveNote = true))
      test(node, "canUnresolveThread") shouldBe false
    }

    it("is false when the account may not resolve notes") {
      val node = threadNode(resolved = true, resolvable = true, permissions = perms(resolveNote = false))
      test(node, "canUnresolveThread") shouldBe false
    }
  }

  describe("the remaining negative permission cases") {
    it("canReplyToThread is false when the account may not create notes") {
      test(threadNode(permissions = perms(createNote = false)), "canReplyToThread") shouldBe false
    }

    it("canEditNote is false when the account may not administer the note") {
      test(noteNode(perms(adminNote = false)), "canEditNote") shouldBe false
    }

    it("canDeleteNote is false when the account may not administer the note") {
      test(noteNode(perms(adminNote = false)), "canDeleteNote") shouldBe false
    }

    it("canCommentOnMergeRequest is false when the section reports canCreateNote = false") {
      test(sectionNode(canCreateNote = false), "canCommentOnMergeRequest") shouldBe false
    }
  }

  describe("wrong receiver types are false regardless of their own permissions") {
    it("a NoteNode asked for canReplyToThread is false even with every permission granted") {
      val node = noteNode(perms(resolveNote = true, adminNote = true, createNote = true))
      test(node, "canReplyToThread") shouldBe false
    }

    it("a ThreadNode asked for canEditNote is false even with every permission granted") {
      val node = threadNode(permissions = perms(resolveNote = true, adminNote = true, createNote = true))
      test(node, "canEditNote") shouldBe false
    }

    it("a DiscussionsSectionNode asked for canDeleteNote is false even when it can create notes") {
      test(sectionNode(canCreateNote = true), "canDeleteNote") shouldBe false
    }
  }

  describe("null receiver and unknown properties") {
    it("a null receiver is false for every one of the six properties") {
      ALL_PROPERTIES.forEach { property -> test(null, property) shouldBe false }
    }

    it("an unknown property name is false") {
      test(threadNode(permissions = perms(createNote = true)), "canDoSomethingElse") shouldBe false
    }

    it("a null property name is false") {
      test(threadNode(permissions = perms(createNote = true)), null) shouldBe false
    }
  }

  describe("the tester and the exported predicates never disagree") {
    it("agrees with canReplyToThread / canResolveThread / canUnresolveThread over the whole thread matrix") {
      val nodes = listOf(true, false).flatMap { resolvable ->
        listOf(true, false).flatMap { resolved ->
          listOf(true, false).flatMap { resolveNote ->
            listOf(true, false).map { createNote ->
              threadNode(resolved, resolvable, perms(resolveNote = resolveNote, createNote = createNote))
            }
          }
        }
      }
      nodes.forEach { node ->
        tester.test(node, "canReplyToThread", null, null) shouldBe canReplyToThread(node)
        tester.test(node, "canResolveThread", null, null) shouldBe canResolveThread(node)
        tester.test(node, "canUnresolveThread", null, null) shouldBe canUnresolveThread(node)
      }
    }

    it("agrees with canEditNote / canDeleteNote over the whole note matrix") {
      listOf(true, false).forEach { adminNote ->
        val node = noteNode(perms(adminNote = adminNote))
        tester.test(node, "canEditNote", null, null) shouldBe canEditNote(node)
        tester.test(node, "canDeleteNote", null, null) shouldBe canDeleteNote(node)
      }
    }

    it("agrees with canCommentOnMergeRequest over the whole section matrix") {
      listOf(true, false).forEach { canCreateNote ->
        val node = sectionNode(canCreateNote)
        tester.test(node, "canCommentOnMergeRequest", null, null) shouldBe canCommentOnMergeRequest(node)
      }
    }
  }
})
