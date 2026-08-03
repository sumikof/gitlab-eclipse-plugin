package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.NoteChangedException
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode
import com.gitlab.eclipse.views.sidebar.NoteNode
import com.gitlab.eclipse.views.sidebar.ThreadNode
import com.gitlab.eclipse.views.sidebar.canResolveThread
import com.gitlab.eclipse.views.sidebar.canUnresolveThread
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp-actions"
private const val PROJECT_ID = 7L
private const val MR_IID = 42L
private const val MR_GID = "gid://gitlab/MergeRequest/99"
private const val REPLY_ID = "gid://gitlab/Discussion/dis-1"
private const val NOTE_GID = "gid://gitlab/Note/123"

private fun threadNode(
  resolved: Boolean = false,
  resolvable: Boolean = true,
  resolveNote: Boolean = true,
) = ThreadNode(
  sourceInstanceUrl = SRC_URL,
  sourceAuthFingerprint = SRC_FP,
  projectId = PROJECT_ID,
  mrIid = MR_IID,
  mrGid = MR_GID,
  mrSha = "abc123",
  namespaceWithPath = "group/project",
  replyId = REPLY_ID,
  resolved = resolved,
  resolvable = resolvable,
  position = null,
  permissions = GitLabNotePermissions(resolveNote = resolveNote, adminNote = false, createNote = false),
  children = emptyList(),
)

private fun noteNode() = NoteNode(
  sourceInstanceUrl = SRC_URL,
  sourceAuthFingerprint = SRC_FP,
  projectId = PROJECT_ID,
  mrIid = MR_IID,
  mrGid = MR_GID,
  mrSha = "abc123",
  namespaceWithPath = "group/project",
  noteGid = NOTE_GID,
  replyId = REPLY_ID,
  body = "a comment",
  authorUsername = "alice",
  createdAt = "2026-01-01T00:00:00Z",
  permissions = GitLabNotePermissions(resolveNote = false, adminNote = true, createNote = false),
)

private fun sectionNode() = DiscussionsSectionNode(
  sourceInstanceUrl = SRC_URL,
  sourceAuthFingerprint = SRC_FP,
  projectId = PROJECT_ID,
  mrIid = MR_IID,
  mrGid = MR_GID,
  mrSha = "abc123",
  namespaceWithPath = "group/project",
)

/**
 * The decisions the five handlers make that can be exercised without SWT: the command-id → target
 * state mapping, the permission predicate that mapping selects, the write target lifted off each
 * node type, and the edit flow's retry message. Everything else in the handlers is workbench-bound
 * and covered by the PR's manual checklist.
 */
class DiscussionActionDecisionsTest : DescribeSpec({

  describe("resolveTargetStateFor") {
    it("maps the Resolve command id to the target state true") {
      resolveTargetStateFor(RESOLVE_THREAD_COMMAND_ID) shouldBe true
    }

    it("maps the Unresolve command id to the target state false") {
      resolveTargetStateFor(UNRESOLVE_THREAD_COMMAND_ID) shouldBe false
    }

    it("maps an unrecognised command id to null") {
      resolveTargetStateFor("com.gitlab.eclipse.commands.SomethingElse") shouldBe null
    }

    it("maps a null command id to null") {
      resolveTargetStateFor(null) shouldBe null
    }
  }

  describe("canApplyResolution picks the same predicate the context menu evaluated") {
    it("delegates to canResolveThread when the target state is resolved") {
      listOf(true, false).forEach { resolvable ->
        listOf(true, false).forEach { resolved ->
          val node = threadNode(resolved = resolved, resolvable = resolvable)
          canApplyResolution(node, resolved = true) shouldBe canResolveThread(node)
        }
      }
    }

    it("delegates to canUnresolveThread when the target state is unresolved") {
      listOf(true, false).forEach { resolvable ->
        listOf(true, false).forEach { resolved ->
          val node = threadNode(resolved = resolved, resolvable = resolvable)
          canApplyResolution(node, resolved = false) shouldBe canUnresolveThread(node)
        }
      }
    }

    it("is false for both target states when the account may not resolve notes") {
      val node = threadNode(resolved = false, resolvable = true, resolveNote = false)
      canApplyResolution(node, resolved = true) shouldBe false
      canApplyResolution(node, resolved = false) shouldBe false
    }
  }

  describe("writeTargetOf takes the node's own connection tags, never a fresh capture") {
    it("reads them off a ThreadNode") {
      writeTargetOf(threadNode()) shouldBe DiscussionWriteTarget(SRC_URL, SRC_FP, PROJECT_ID, MR_IID)
    }

    it("reads them off a NoteNode") {
      writeTargetOf(noteNode()) shouldBe DiscussionWriteTarget(SRC_URL, SRC_FP, PROJECT_ID, MR_IID)
    }

    it("reads them off a DiscussionsSectionNode") {
      writeTargetOf(sectionNode()) shouldBe DiscussionWriteTarget(SRC_URL, SRC_FP, PROJECT_ID, MR_IID)
    }
  }

  describe("editRetryMessage") {
    it("explains the concurrent edit when the write failed with NoteChangedException") {
      val outcome = DiscussionWriteOutcome.Definite(NoteChangedException())
      editRetryMessage(outcome, "fallback") shouldBe NOTE_CHANGED_MESSAGE
    }

    it("keeps the launcher's message for any other Definite failure") {
      val outcome = DiscussionWriteOutcome.Definite(GitLabApiException(403, "forbidden", null))
      editRetryMessage(outcome, "fallback") shouldBe "fallback"
    }

    it("keeps the launcher's message for an Ambiguous failure") {
      val outcome = DiscussionWriteOutcome.Ambiguous(NoteChangedException())
      editRetryMessage(outcome, "fallback") shouldBe "fallback"
    }

    it("keeps the launcher's message when no outcome was recorded yet") {
      editRetryMessage(null, "fallback") shouldBe "fallback"
    }
  }
})
