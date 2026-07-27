package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SidebarViewModelChangedFilesTest : StringSpec({
  val vm = SidebarViewModel()

  fun diff(
    oldPath: String? = null,
    newPath: String? = null,
    newFile: Boolean = false,
    deletedFile: Boolean = false,
    renamedFile: Boolean = false,
  ) = GitLabMrVersion.Diff(oldPath, newPath, newFile, deletedFile, renamedFile)

  fun version(diffs: List<GitLabMrVersion.Diff>, headCommitSha: String? = "headsha") =
    GitLabMrVersion(id = 1L, headCommitSha = headCommitSha, diffs = diffs)

  "LIST mode: flat ChangedFileNodes in diff order" {
    val v =
      version(
        listOf(
          diff(oldPath = "a.kt", newPath = "a.kt"),
          diff(oldPath = "b.kt", newPath = "b.kt"),
          diff(oldPath = "c.kt", newPath = "c.kt"),
        ),
      )

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    nodes shouldHaveSize 3
    nodes.forEach { (it is ChangedFileNode) shouldBe true }
    (nodes[0] as ChangedFileNode).newPath shouldBe "a.kt"
    (nodes[1] as ChangedFileNode).newPath shouldBe "b.kt"
    (nodes[2] as ChangedFileNode).newPath shouldBe "c.kt"
  }

  "LIST mode: diffHeadSha comes from version.headCommitSha" {
    val v = version(listOf(diff(oldPath = "a.kt", newPath = "a.kt")), headCommitSha = "sha123")

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    (nodes[0] as ChangedFileNode).diffHeadSha shouldBe "sha123"
  }

  "TREE mode: groups files under a shared directory, leaves root file ungrouped" {
    val v =
      version(
        listOf(
          diff(oldPath = "src/main/A.kt", newPath = "src/main/A.kt"),
          diff(oldPath = "src/main/B.kt", newPath = "src/main/B.kt"),
          diff(oldPath = "README.md", newPath = "README.md"),
        ),
      )

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.TREE)

    nodes shouldHaveSize 2
    val dir = nodes.filterIsInstance<ChangedDirectoryNode>().single()
    dir.label shouldBe "src/main"
    dir.children shouldHaveSize 2
    dir.children.map { (it as ChangedFileNode).newPath }.toSet() shouldBe setOf("src/main/A.kt", "src/main/B.kt")
    val rootFile = nodes.filterIsInstance<ChangedFileNode>().single()
    rootFile.newPath shouldBe "README.md"
  }

  "TREE mode: collapses a single-child directory chain into one node" {
    val v = version(listOf(diff(oldPath = "a/b/c/only.kt", newPath = "a/b/c/only.kt")))

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.TREE)

    nodes shouldHaveSize 1
    val dir = nodes[0] as ChangedDirectoryNode
    dir.label shouldBe "a/b/c"
    dir.children shouldHaveSize 1
    (dir.children[0] as ChangedFileNode).newPath shouldBe "a/b/c/only.kt"
  }

  "changeType: deletedFile -> DELETED, display path from oldPath" {
    val v = version(listOf(diff(oldPath = "gone.kt", newPath = null, deletedFile = true)))

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    val node = nodes[0] as ChangedFileNode
    node.changeType shouldBe ChangeType.DELETED
    node.oldPath shouldBe "gone.kt"
    node.newPath shouldBe null
    node.label shouldBe "gone.kt"
  }

  "changeType: newFile -> NEW" {
    val v = version(listOf(diff(oldPath = null, newPath = "added.kt", newFile = true)))

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    (nodes[0] as ChangedFileNode).changeType shouldBe ChangeType.NEW
  }

  "changeType: renamedFile -> RENAMED" {
    val v = version(listOf(diff(oldPath = "old.kt", newPath = "new.kt", renamedFile = true)))

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    (nodes[0] as ChangedFileNode).changeType shouldBe ChangeType.RENAMED
  }

  "changeType: plain diff -> MODIFIED" {
    val v = version(listOf(diff(oldPath = "x.kt", newPath = "x.kt")))

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    (nodes[0] as ChangedFileNode).changeType shouldBe ChangeType.MODIFIED
  }

  "empty diffs shows 'No changed files' message node" {
    val v = version(emptyList())

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.LIST)

    nodes shouldHaveSize 1
    (nodes[0] as MessageNode).label shouldBe "No changed files"
  }

  "null diffs (Gson-built version without a diffs key) shows 'No changed files' message node" {
    val json = """{"id":1,"head_commit_sha":"headsha"}"""
    val v = Gson().fromJson(json, GitLabMrVersion::class.java)

    val nodes = vm.buildChangedFileNodes(v, SidebarViewMode.TREE)

    nodes shouldHaveSize 1
    (nodes[0] as MessageNode).label shouldBe "No changed files"
  }
})
