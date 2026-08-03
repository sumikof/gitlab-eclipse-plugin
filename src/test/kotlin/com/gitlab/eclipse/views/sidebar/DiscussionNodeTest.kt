package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Pure value tests for the discussion sidebar nodes: no SWT, no Display, no workbench, so they
 * run in the headless container.
 */
class DiscussionNodeTest : DescribeSpec({
  val perms = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true)

  fun section() =
    DiscussionsSectionNode(
      sourceInstanceUrl = "https://gitlab.example.com",
      sourceAuthFingerprint = "fp-1",
      projectId = 42L,
      mrIid = 7L,
      mrGid = "gid://gitlab/MergeRequest/99",
      mrSha = "abc123",
      namespaceWithPath = "group/sub/proj",
    )

  fun position(
    newPath: String? = null,
    oldPath: String? = null,
    newLine: Int? = null,
    oldLine: Int? = null,
  ) = GitLabNotePosition(
    positionType = "text",
    newPath = newPath,
    oldPath = oldPath,
    newLine = newLine,
    oldLine = oldLine,
  )

  fun thread(
    position: GitLabNotePosition? = null,
    resolved: Boolean = false,
    resolvable: Boolean = false,
    children: List<SidebarNode> = emptyList(),
  ) = ThreadNode(
    sourceInstanceUrl = "https://gitlab.example.com",
    sourceAuthFingerprint = "fp-1",
    projectId = 42L,
    mrIid = 7L,
    mrGid = "gid://gitlab/MergeRequest/99",
    mrSha = "abc123",
    namespaceWithPath = "group/sub/proj",
    replyId = "gid://gitlab/DiscussionNote/1",
    resolved = resolved,
    resolvable = resolvable,
    position = position,
    permissions = perms,
    children = children,
  )

  fun note(body: String = "hello", authorUsername: String = "octocat") =
    NoteNode(
      sourceInstanceUrl = "https://gitlab.example.com",
      sourceAuthFingerprint = "fp-1",
      projectId = 42L,
      mrIid = 7L,
      mrGid = "gid://gitlab/MergeRequest/99",
      mrSha = "abc123",
      namespaceWithPath = "group/sub/proj",
      noteGid = "gid://gitlab/Note/555",
      replyId = "gid://gitlab/DiscussionNote/1",
      body = body,
      authorUsername = authorUsername,
      createdAt = "2026-01-02T03:04:05Z",
      permissions = perms,
    )

  describe("DiscussionsSectionNode") {
    it("labels itself Discussions and is not activatable") {
      val node = section()

      node.label shouldBe "Discussions"
      node.activationUrl shouldBe null
    }

    it("starts NOT_LOADED, without create permission and without loaded children") {
      val node = section()

      node.loadState shouldBe DiscussionLoadState.NOT_LOADED
      node.canCreateNote shouldBe false
      node.loadedChildren shouldBe null
    }

    it("renders one stable Loading placeholder instance until children are loaded") {
      val node = section()

      val first = node.children
      val second = node.children

      first shouldHaveSize 1
      (first[0] as MessageNode).label shouldBe LOADING_MESSAGE
      // Identity, not equality: JFace tracks tree elements by identity, so a fresh list or
      // MessageNode per read would churn the widget mapping.
      (first === second) shouldBe true
      (first[0] === second[0]) shouldBe true
    }

    it("returns exactly the loaded children once they are set") {
      val node = section()
      val loaded = listOf(thread(), thread())

      node.loadedChildren = loaded

      (node.children === loaded) shouldBe true
      node.children shouldHaveSize 2
    }

    it("retains its connection tags and merge-request identifiers") {
      val node = section()

      node.sourceInstanceUrl shouldBe "https://gitlab.example.com"
      node.sourceAuthFingerprint shouldBe "fp-1"
      node.projectId shouldBe 42L
      node.mrIid shouldBe 7L
      node.mrGid shouldBe "gid://gitlab/MergeRequest/99"
      node.mrSha shouldBe "abc123"
      node.namespaceWithPath shouldBe "group/sub/proj"
    }
  }

  describe("ThreadNode") {
    it("retains its connection tags, identifiers, resolution state, position and children") {
      val child = note()
      val pos = position(newPath = "src/main/App.kt", newLine = 42)
      val node = thread(position = pos, resolved = true, resolvable = true, children = listOf(child))

      node.sourceInstanceUrl shouldBe "https://gitlab.example.com"
      node.sourceAuthFingerprint shouldBe "fp-1"
      node.projectId shouldBe 42L
      node.mrIid shouldBe 7L
      node.mrGid shouldBe "gid://gitlab/MergeRequest/99"
      node.mrSha shouldBe "abc123"
      node.namespaceWithPath shouldBe "group/sub/proj"
      node.replyId shouldBe "gid://gitlab/DiscussionNote/1"
      node.resolved shouldBe true
      node.resolvable shouldBe true
      node.position shouldBe pos
      node.permissions shouldBe perms
      node.children shouldHaveSize 1
      (node.children[0] === child) shouldBe true
    }

    describe("label location part (resolvable = false, so no suffix interferes)") {
      it("rule 1: a null position is an overall comment") {
        thread(position = null).label shouldBe "(overall)"
      }

      it("rule 2: new path + new line") {
        thread(position = position(newPath = "src/main/App.kt", newLine = 42)).label shouldBe
          "src/main/App.kt:42"
      }

      it("rule 3: new path without a new line") {
        thread(position = position(newPath = "src/main/App.kt")).label shouldBe "src/main/App.kt"
      }

      it("rule 4: old path + old line when there is no new path") {
        thread(position = position(oldPath = "src/main/Gone.kt", oldLine = 7)).label shouldBe
          "src/main/Gone.kt:7"
      }

      it("rule 5: old path without an old line when there is no new path") {
        thread(position = position(oldPath = "src/main/Gone.kt")).label shouldBe "src/main/Gone.kt"
      }

      it("rule 6: a position with neither path is an overall comment") {
        thread(position = position(newLine = 3, oldLine = 4)).label shouldBe "(overall)"
      }
    }

    describe("label resolution suffix") {
      it("omits the suffix entirely when the thread is not resolvable") {
        thread(position = null, resolvable = false, resolved = true).label shouldBe "(overall)"
      }

      it("appends Resolved when resolvable and resolved") {
        thread(position = null, resolvable = true, resolved = true).label shouldBe
          "(overall) · Resolved"
      }

      it("appends Unresolved when resolvable and not resolved") {
        thread(position = null, resolvable = true, resolved = false).label shouldBe
          "(overall) · Unresolved"
      }
    }

    it("combines the location and the suffix for a diff-anchored unresolved thread") {
      val node =
        thread(
          position = position(newPath = "src/main/App.kt", newLine = 42),
          resolvable = true,
          resolved = false,
        )

      node.label shouldBe "src/main/App.kt:42 · Unresolved"
    }
  }

  describe("NoteNode") {
    it("retains all of its fields, including noteGid and replyId") {
      val node = note()

      node.sourceInstanceUrl shouldBe "https://gitlab.example.com"
      node.sourceAuthFingerprint shouldBe "fp-1"
      node.projectId shouldBe 42L
      node.mrIid shouldBe 7L
      node.mrGid shouldBe "gid://gitlab/MergeRequest/99"
      node.mrSha shouldBe "abc123"
      node.namespaceWithPath shouldBe "group/sub/proj"
      node.noteGid shouldBe "gid://gitlab/Note/555"
      node.replyId shouldBe "gid://gitlab/DiscussionNote/1"
      node.body shouldBe "hello"
      node.authorUsername shouldBe "octocat"
      node.createdAt shouldBe "2026-01-02T03:04:05Z"
      node.permissions shouldBe perms
      node.children shouldHaveSize 0
      node.activationUrl shouldBe null
    }

    it("labels a single-line body as author plus body") {
      note(body = "Looks good to me").label shouldBe "@octocat: Looks good to me"
    }

    it("truncates a body longer than 120 characters and ends with a single ellipsis") {
      val body = "x".repeat(130)

      val label = note(body = body).label

      label shouldBe "@octocat: " + "x".repeat(120) + "…"
      label.endsWith("…") shouldBe true
      // "@octocat: " is 10 chars, then 120 body chars, then 1 ellipsis char.
      label.length shouldBe 131
      label.removePrefix("@octocat: ").length shouldBe 121
    }

    it("does not truncate a body of exactly 120 characters") {
      val body = "y".repeat(120)

      val label = note(body = body).label

      label shouldBe "@octocat: " + "y".repeat(120)
      label.contains("…") shouldBe false
      label.length shouldBe 130
    }

    it("keeps only the first line of a multi-line body (LF)") {
      note(body = "first line\nsecond line\nthird").label shouldBe "@octocat: first line"
    }

    it("keeps only the first line of a multi-line body (CRLF)") {
      note(body = "first line\r\nsecond line").label shouldBe "@octocat: first line"
    }

    it("collapses internal whitespace runs and trims the summary") {
      note(body = "  spaced\t\tout    words  ").label shouldBe "@octocat: spaced out words"
    }

    it("omits the colon entirely for a blank body") {
      note(body = "   \t  ").label shouldBe "@octocat"
      note(body = "").label shouldBe "@octocat"
    }
  }
})
