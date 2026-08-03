package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.TruncationReason
import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure composition tests for the discussions subtree and for `buildMrChildren`'s Discussions
 * section: no SWT, no Display, no workbench, so they run in the headless container.
 */
class SidebarViewModelDiscussionsTest : DescribeSpec({
  val vm = SidebarViewModel()

  val firstPermissions = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true)
  val secondPermissions = GitLabNotePermissions(resolveNote = false, adminNote = true, createNote = false)

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

  fun position(newPath: String, newLine: Int) =
    GitLabNotePosition(
      positionType = "text",
      newPath = newPath,
      oldPath = null,
      newLine = newLine,
      oldLine = null,
    )

  fun note(
    id: String,
    body: String = "a comment",
    author: String = "alice",
    createdAt: String = "2024-01-01T00:00:00Z",
    permissions: GitLabNotePermissions = firstPermissions,
    position: GitLabNotePosition? = null,
  ) = GitLabNote(
    id = id,
    createdAt = createdAt,
    system = false,
    authorUsername = author,
    body = body,
    permissions = permissions,
    position = position,
  )

  fun discussion(
    replyId: String = "gid://gitlab/Discussion/1",
    notes: List<GitLabNote> = listOf(note("gid://gitlab/Note/1")),
    resolved: Boolean = false,
    resolvable: Boolean = true,
    hasMoreNotes: Boolean = false,
  ) = GitLabDiscussion(
    replyId = replyId,
    createdAt = "2024-01-01T00:00:00Z",
    resolved = resolved,
    resolvable = resolvable,
    notes = notes,
    hasMoreNotes = hasMoreNotes,
  )

  fun result(
    discussions: List<GitLabDiscussion>,
    truncation: TruncationReason? = null,
  ) = DiscussionsReadResult(canCreateNote = true, discussions = discussions, truncation = truncation)

  describe("buildDiscussionChildren") {
    it("renders exactly one 'No discussions' message when the fetch returned no discussions") {
      val children = vm.buildDiscussionChildren(section(), result(emptyList()))

      children shouldHaveSize 1
      children.single().shouldBeInstanceOf<MessageNode>().label shouldBe "No discussions"
    }

    it("renders one ThreadNode per discussion, holding its notes as NoteNodes in order") {
      val d = discussion(notes = listOf(note("gid://gitlab/Note/1"), note("gid://gitlab/Note/2")))

      val children = vm.buildDiscussionChildren(section(), result(listOf(d)))

      children shouldHaveSize 1
      val thread = children.single().shouldBeInstanceOf<ThreadNode>()
      thread.children shouldHaveSize 2
      thread.children[0].shouldBeInstanceOf<NoteNode>().noteGid shouldBe "gid://gitlab/Note/1"
      thread.children[1].shouldBeInstanceOf<NoteNode>().noteGid shouldBe "gid://gitlab/Note/2"
    }

    it("copies the section's connection tags and all five MR identifiers onto the ThreadNode") {
      val node = section()
      val d = discussion(replyId = "gid://gitlab/Discussion/77", resolved = true, resolvable = true)

      val thread = vm.buildDiscussionChildren(node, result(listOf(d))).single().shouldBeInstanceOf<ThreadNode>()

      thread.sourceInstanceUrl shouldBe node.sourceInstanceUrl
      thread.sourceAuthFingerprint shouldBe node.sourceAuthFingerprint
      thread.projectId shouldBe node.projectId
      thread.mrIid shouldBe node.mrIid
      thread.mrGid shouldBe node.mrGid
      thread.mrSha shouldBe node.mrSha
      thread.namespaceWithPath shouldBe node.namespaceWithPath
      thread.replyId shouldBe "gid://gitlab/Discussion/77"
      thread.resolved shouldBe true
      thread.resolvable shouldBe true
    }

    it("copies the tags, the identifiers, each note's own id and the THREAD's replyId onto every NoteNode") {
      val node = section()
      val d =
        discussion(
          replyId = "gid://gitlab/Discussion/77",
          notes = listOf(
            note("gid://gitlab/Note/1", body = "first", author = "alice", createdAt = "2024-01-01T00:00:00Z"),
            note("gid://gitlab/Note/2", body = "second", author = "bob", createdAt = "2024-01-02T00:00:00Z"),
          ),
        )

      val thread = vm.buildDiscussionChildren(node, result(listOf(d))).single().shouldBeInstanceOf<ThreadNode>()
      val notes = thread.children.map { it.shouldBeInstanceOf<NoteNode>() }

      notes.forEach {
        it.sourceInstanceUrl shouldBe node.sourceInstanceUrl
        it.sourceAuthFingerprint shouldBe node.sourceAuthFingerprint
        it.projectId shouldBe node.projectId
        it.mrIid shouldBe node.mrIid
        it.mrGid shouldBe node.mrGid
        it.mrSha shouldBe node.mrSha
        it.namespaceWithPath shouldBe node.namespaceWithPath
        // The thread's reply handle, never the note's own id — that is how a note-level action
        // addresses its thread without a parent pointer.
        it.replyId shouldBe "gid://gitlab/Discussion/77"
      }
      notes[0].noteGid shouldBe "gid://gitlab/Note/1"
      notes[0].body shouldBe "first"
      notes[0].authorUsername shouldBe "alice"
      notes[0].createdAt shouldBe "2024-01-01T00:00:00Z"
      notes[1].noteGid shouldBe "gid://gitlab/Note/2"
      notes[1].body shouldBe "second"
      notes[1].authorUsername shouldBe "bob"
      notes[1].createdAt shouldBe "2024-01-02T00:00:00Z"
      notes[0].permissions shouldBe firstPermissions
    }

    it("takes the thread's position and permissions from the FIRST note, not a later one") {
      val d =
        discussion(
          notes = listOf(
            note("gid://gitlab/Note/1", permissions = firstPermissions, position = position("src/A.kt", 10)),
            note("gid://gitlab/Note/2", permissions = secondPermissions, position = position("src/B.kt", 99)),
          ),
        )

      val thread = vm.buildDiscussionChildren(section(), result(listOf(d))).single().shouldBeInstanceOf<ThreadNode>()

      thread.position.shouldNotBeNull().newPath shouldBe "src/A.kt"
      thread.position.shouldNotBeNull().newLine shouldBe 10
      thread.permissions shouldBe firstPermissions
      // Sanity: the second note really carried different values, so the assertion is not vacuous.
      thread.children[1].shouldBeInstanceOf<NoteNode>().permissions shouldBe secondPermissions
    }

    it("appends the 'more replies' marker as the thread's last child only when hasMoreNotes is true") {
      val notes = listOf(note("gid://gitlab/Note/1"), note("gid://gitlab/Note/2"))

      val withMore =
        vm.buildDiscussionChildren(section(), result(listOf(discussion(notes = notes, hasMoreNotes = true))))
          .single()
          .shouldBeInstanceOf<ThreadNode>()
      val withoutMore =
        vm.buildDiscussionChildren(section(), result(listOf(discussion(notes = notes, hasMoreNotes = false))))
          .single()
          .shouldBeInstanceOf<ThreadNode>()

      withMore.children shouldHaveSize 3
      withMore.children.last().shouldBeInstanceOf<MessageNode>().label shouldBe "(more replies — open in GitLab)"
      withoutMore.children shouldHaveSize 2
      withoutMore.children.none { it is MessageNode } shouldBe true
    }

    it("appends the truncation marker as the section's last child for either reason, and never when null") {
      val truncatedMessage = "(truncated — open the merge request in GitLab to see all discussions)"
      val one = listOf(discussion())

      val pageLimit = vm.buildDiscussionChildren(section(), result(one, TruncationReason.PAGE_LIMIT))
      val deadline = vm.buildDiscussionChildren(section(), result(one, TruncationReason.DEADLINE))
      val complete = vm.buildDiscussionChildren(section(), result(one, null))

      pageLimit shouldHaveSize 2
      pageLimit.last().shouldBeInstanceOf<MessageNode>().label shouldBe truncatedMessage
      deadline shouldHaveSize 2
      deadline.last().shouldBeInstanceOf<MessageNode>().label shouldBe truncatedMessage
      complete shouldHaveSize 1
      complete.single().shouldBeInstanceOf<ThreadNode>()
    }

    it("shows both 'No discussions' and the truncation marker when an empty fetch was truncated") {
      val children = vm.buildDiscussionChildren(section(), result(emptyList(), TruncationReason.DEADLINE))

      children shouldHaveSize 2
      children[0].shouldBeInstanceOf<MessageNode>().label shouldBe "No discussions"
      children[1].shouldBeInstanceOf<MessageNode>().label shouldBe
        "(truncated — open the merge request in GitLab to see all discussions)"
    }

    it("skips a discussion whose note list is empty instead of throwing") {
      val discussions =
        listOf(
          discussion(replyId = "gid://gitlab/Discussion/empty", notes = emptyList()),
          discussion(replyId = "gid://gitlab/Discussion/kept"),
        )

      val children = vm.buildDiscussionChildren(section(), result(discussions))

      children shouldHaveSize 1
      children.single().shouldBeInstanceOf<ThreadNode>().replyId shouldBe "gid://gitlab/Discussion/kept"
    }
  }

  describe("buildDiscussionFailureChildren / buildDiscussionLoadingChildren") {
    it("returns one message per failure kind, and the gate-rejected wording differs from the generic one") {
      val failed = vm.buildDiscussionFailureChildren(gateRejected = false)
      val gated = vm.buildDiscussionFailureChildren(gateRejected = true)

      failed shouldHaveSize 1
      failed.single().shouldBeInstanceOf<MessageNode>().label shouldBe "Failed to load discussions — see the Error Log."
      gated shouldHaveSize 1
      gated.single().shouldBeInstanceOf<MessageNode>().label shouldBe "Connection changed — refresh the view."
      failed.single().label shouldNotBe gated.single().label
    }

    it("renders the loading placeholder with the one shared LOADING_MESSAGE constant") {
      val children = vm.buildDiscussionLoadingChildren()

      children shouldHaveSize 1
      children.single().shouldBeInstanceOf<MessageNode>().label shouldBe LOADING_MESSAGE
    }
  }

  describe("buildMrChildren: Discussions section") {
    val mrUrl = "https://gitlab.example.com/group/sub/proj/-/merge_requests/42"

    fun mr(references: GitLabMergeRequest.Reference? = GitLabMergeRequest.Reference("group/sub/proj!42")) =
      GitLabMergeRequest(
        id = 987L,
        iid = 42L,
        title = "A merge request",
        projectId = 7L,
        webUrl = mrUrl,
        state = "opened",
        sha = "headsha",
        references = references,
      )

    val version =
      GitLabMrVersion(
        id = 1L,
        headCommitSha = "headsha",
        diffs = listOf(GitLabMrVersion.Diff("src/A.kt", "src/A.kt", false, false, false)),
      )

    it("inserts the section between Overview and the changed files, with the GID built from mr.id") {
      val children =
        vm.buildMrChildren(
          mrUrl,
          Result.success(version),
          SidebarViewMode.LIST,
          mr(),
          "https://gitlab.example.com/",
          "fp-1",
        )

      children shouldHaveSize 3
      children[0].shouldBeInstanceOf<OverviewNode>()
      val sectionNode = children[1].shouldBeInstanceOf<DiscussionsSectionNode>()
      children[2].shouldBeInstanceOf<ChangedFileNode>().newPath shouldBe "src/A.kt"
      // mr.id (987), never mr.iid (42): they are different numbers and the GID needs the global id.
      sectionNode.mrGid shouldBe "gid://gitlab/MergeRequest/987"
      sectionNode.mrGid shouldContain "987"
      sectionNode.mrGid shouldNotContain "42"
      sectionNode.mrIid shouldBe 42L
      sectionNode.projectId shouldBe 7L
      sectionNode.mrSha shouldBe "headsha"
      sectionNode.namespaceWithPath shouldBe "group/sub/proj"
      sectionNode.sourceInstanceUrl shouldBe "https://gitlab.example.com/"
      sectionNode.sourceAuthFingerprint shouldBe "fp-1"
      sectionNode.loadState shouldBe DiscussionLoadState.NOT_LOADED
    }

    it("omits the section — leaving exactly today's children — when a tag or the reference is missing") {
      val baseline = vm.buildMrChildren(mrUrl, Result.success(version), SidebarViewMode.LIST).map { it.label }

      val noInstanceUrl =
        vm.buildMrChildren(mrUrl, Result.success(version), SidebarViewMode.LIST, mr(), null, "fp-1")
      val noFingerprint =
        vm.buildMrChildren(
          mrUrl,
          Result.success(version),
          SidebarViewMode.LIST,
          mr(),
          "https://gitlab.example.com",
          null,
        )
      val noReferences =
        vm.buildMrChildren(
          mrUrl,
          Result.success(version),
          SidebarViewMode.LIST,
          mr(references = null),
          "https://gitlab.example.com",
          "fp-1",
        )

      baseline shouldHaveSize 2
      listOf(noInstanceUrl, noFingerprint, noReferences).forEach { children ->
        children.none { it is DiscussionsSectionNode } shouldBe true
        children.map { it.label } shouldBe baseline
        children[0].shouldBeInstanceOf<OverviewNode>()
        children[1].shouldBeInstanceOf<ChangedFileNode>()
      }
    }

    it("keeps the pre-existing three-argument call producing today's children") {
      val children = vm.buildMrChildren(mrUrl, Result.success(version), SidebarViewMode.LIST)

      children shouldHaveSize 2
      children[0].shouldBeInstanceOf<OverviewNode>().label shouldBe "Overview"
      children[1].shouldBeInstanceOf<ChangedFileNode>().newPath shouldBe "src/A.kt"
      children.none { it is DiscussionsSectionNode } shouldBe true
    }
  }
})
