package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import kotlin.io.path.createTempDirectory

/** Real repositories: the contract is what `git status` reports afterwards. */
class PatchIndexUpdaterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun newRepo(): Pair<File, Git> {
    val dir = createTempDirectory("patch-index").toFile()
    return dir to Git.init().setDirectory(dir).setInitialBranch("main").call()
  }

  fun commit(git: Git, dir: File, name: String, text: String) {
    File(dir, name).writeText(text)
    git.add().addFilepattern(name).call()
    git.commit().setMessage("m").setAuthor("t", "t@example.com").setSign(false).call()
  }

  fun openRepo(dir: File) = FileRepositoryBuilder().setGitDir(File(dir, ".git")).build()

  fun modify(path: String, text: String, mode: FileMode = FileMode.REGULAR_FILE) =
    PatchChange(path, PatchChangeKind.MODIFY, text.toByteArray(), mode)

  describe("update") {
    it("stages the post-image so the working tree reports clean") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      File(dir, "A.txt").writeText("b\n")

      val result = openRepo(dir).use { repo ->
        PatchIndexUpdater().update(repo, dir, listOf(modify("A.txt", "b\n")), repo.resolve("HEAD^{tree}"))
      }

      result shouldBe IndexUpdateResult.Ok
      Git.open(dir).use { it.status().call().isClean shouldBe false }
      Git.open(dir).use { it.status().call().modified shouldBe emptySet() }
      Git.open(dir).use { it.status().call().changed shouldBe setOf("A.txt") }
    }

    it("removes the entry for a deleted path") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      File(dir, "A.txt").delete()
      val change = PatchChange("A.txt", PatchChangeKind.DELETE, null, null)

      openRepo(dir).use { repo ->
        PatchIndexUpdater().update(repo, dir, listOf(change), repo.resolve("HEAD^{tree}"))
      }

      openRepo(dir).use { DirCache.read(it).getEntry("A.txt") shouldBe null }
    }

    it("adds an entry for a new path") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      File(dir, "New.txt").writeText("new\n")
      val change = PatchChange("New.txt", PatchChangeKind.ADD, "new\n".toByteArray(), FileMode.REGULAR_FILE)

      openRepo(dir).use { repo ->
        PatchIndexUpdater().update(repo, dir, listOf(change), repo.resolve("HEAD^{tree}"))
      }

      Git.open(dir).use { it.status().call().added shouldBe setOf("New.txt") }
    }

    it("keeps the executable bit in the index") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      File(dir, "A.txt").writeText("b\n")
      File(dir, "A.txt").setExecutable(true)

      openRepo(dir).use { repo ->
        PatchIndexUpdater().update(
          repo,
          dir,
          listOf(modify("A.txt", "b\n", FileMode.EXECUTABLE_FILE)),
          repo.resolve("HEAD^{tree}"),
        )
      }

      openRepo(dir).use { DirCache.read(it).getEntry("A.txt").fileMode shouldBe FileMode.EXECUTABLE_FILE }
    }

    it("refuses to write the index when a staged change appeared after the plan") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val headTree = openRepo(dir).use { it.resolve("HEAD^{tree}") }
      val headBlob = openRepo(dir).use { it.resolve("HEAD:A.txt") }
      // An external `git add` lands between the plan and the index update.
      Git.open(dir).use {
        File(dir, "A.txt").writeText("staged\n")
        it.add().addFilepattern("A.txt").call()
      }
      val stagedBlob = openRepo(dir).use { DirCache.read(it).getEntry("A.txt").objectId }

      val result = openRepo(dir).use {
        PatchIndexUpdater().update(it, dir, listOf(modify("A.txt", "c\n")), headTree)
      }

      result shouldBe IndexUpdateResult.Conflicted
      stagedBlob shouldNotBe headBlob
      // The external staging survived untouched.
      openRepo(dir).use { DirCache.read(it).getEntry("A.txt").objectId shouldBe stagedBlob }
    }

    it("leaves the index writable after refusing, so the lock is released") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val headTree = openRepo(dir).use { it.resolve("HEAD^{tree}") }
      Git.open(dir).use {
        File(dir, "A.txt").writeText("staged\n")
        it.add().addFilepattern("A.txt").call()
      }
      openRepo(dir).use { PatchIndexUpdater().update(it, dir, listOf(modify("A.txt", "c\n")), headTree) }

      File(dir, ".git/index.lock").exists() shouldBe false
      Git.open(dir).use { it.add().addFilepattern("A.txt").call() }
    }
  }

  describe("countIndexEntriesDifferingFromHead") {
    it("counts only the paths whose stage-0 entry differs from HEAD") {
      val (dir, git) = newRepo()
      git.use {
        commit(it, dir, "A.txt", "a\n")
        commit(it, dir, "B.txt", "b\n")
        File(dir, "A.txt").writeText("staged\n")
        it.add().addFilepattern("A.txt").call()
      }

      openRepo(dir).use { repo ->
        PatchIndexUpdater.countIndexEntriesDifferingFromHead(
          repo,
          listOf("A.txt", "B.txt"),
          repo.resolve("HEAD^{tree}"),
        ) shouldBe 1
      }
    }

    it("treats a path absent from both the index and HEAD as matching") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }

      openRepo(dir).use { repo ->
        PatchIndexUpdater.countIndexEntriesDifferingFromHead(
          repo,
          listOf("Nowhere.txt"),
          repo.resolve("HEAD^{tree}"),
        ) shouldBe 0
      }
    }

    it("counts a path staged for addition that HEAD does not have") {
      val (dir, git) = newRepo()
      git.use {
        commit(it, dir, "A.txt", "a\n")
        File(dir, "New.txt").writeText("new\n")
        it.add().addFilepattern("New.txt").call()
      }

      openRepo(dir).use { repo ->
        PatchIndexUpdater.countIndexEntriesDifferingFromHead(
          repo,
          listOf("New.txt"),
          repo.resolve("HEAD^{tree}"),
        ) shouldBe 1
      }
    }
  }
})
