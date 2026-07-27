package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

class CurrentBranchGitReaderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  // Every temp dir created below is registered here and wiped in afterSpec (mirrors
  // GitLabProjectUrlResolverTest's temp-dir cleanup pattern).
  val createdTempPaths = mutableListOf<File>()

  fun tempRepo(): Pair<File, Git> {
    val dir = Files.createTempDirectory("branch-repo").toFile()
    createdTempPaths += dir
    val git = Git.init().setDirectory(dir).call()
    return dir to git
  }

  fun commit(git: Git, dir: File, fileName: String = "a.txt") {
    File(dir, fileName).writeText("hello")
    git.add().addFilepattern(".").call()
    git.commit().setMessage("init").setAuthor("t", "t@e").setCommitter("t", "t@e").call()
  }

  afterSpec { createdTempPaths.forEach { it.deleteRecursively() } }

  describe("read") {
    it("returns the branch name and HEAD sha for a committed branch") {
      val (dir, git) = tempRepo()
      git.use { commit(it, dir) }

      val result = CurrentBranchGitReader().read(dir)

      result.name shouldBe "master"
      result.headSha?.length shouldBe 40
      result.hasUpstream shouldBe false
      result.trackingBranch.shouldBeNull()
      result.upstreamRemote.shouldBeNull()
    }

    it("reads tracking branch and upstream remote from git config") {
      val (dir, git) = tempRepo()
      git.use {
        commit(it, dir)
        it.repository.config.apply {
          setString("branch", "master", "remote", "origin")
          setString("branch", "master", "merge", "refs/heads/foo")
          save()
        }
      }

      val result = CurrentBranchGitReader().read(dir)

      result.trackingBranch shouldBe "foo"
      result.upstreamRemote shouldBe "origin"
      result.hasUpstream shouldBe true
    }

    it("returns null tracking/upstream when no upstream is configured") {
      val (dir, git) = tempRepo()
      git.use { commit(it, dir) }

      val result = CurrentBranchGitReader().read(dir)

      result.trackingBranch.shouldBeNull()
      result.upstreamRemote.shouldBeNull()
      result.hasUpstream shouldBe false
      result.name shouldBe "master"
      result.headSha.shouldNotBeNull()
    }

    it("returns null name but a headSha for a detached HEAD") {
      val (dir, git) = tempRepo()
      val sha = git.use {
        commit(it, dir)
        val headSha = it.repository.resolve("HEAD")!!.name
        it.checkout().setName(headSha).call()
        headSha
      }

      val result = CurrentBranchGitReader().read(dir)

      result.name.shouldBeNull()
      result.headSha shouldBe sha
    }

    it("never throws for a non-existent directory, returning all-null CurrentBranch") {
      val bogus = File(Files.createTempDirectory("no-such-repo").toFile(), "missing")
      createdTempPaths += bogus.parentFile

      val result = CurrentBranchGitReader().read(bogus)

      result shouldBe CurrentBranch(null, null, false, null, null)
    }
  }
})
