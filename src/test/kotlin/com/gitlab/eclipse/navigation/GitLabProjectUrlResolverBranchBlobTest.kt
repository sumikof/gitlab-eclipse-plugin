package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.assignments.AssignedProjectLookup
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.File
import java.nio.file.Files

/** NAV-03: [GitLabProjectUrlResolver.resolveBranchBlobUrl] (VS Code `copyLinkToActiveFileOnBranch`). */
class GitLabProjectUrlResolverBranchBlobTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val createdTempPaths = mutableListOf<File>()

  val notInRepo = GitLabProjectUrlResolver.Resolution.Warn("The current file is not in the project repository.")
  val notCommitted = GitLabProjectUrlResolver.Resolution.Warn(
    "No link exists for the current file. Commit the current file to the repository.",
  )
  val detached = GitLabProjectUrlResolver.Resolution.Warn(
    "The repository seems to be in a detached HEAD state. Please check out a branch.",
  )
  val noRemote = GitLabProjectUrlResolver.Resolution.Warn("No GitLab remote is configured for the current project.")
  val mismatch = GitLabProjectUrlResolver.Resolution.Warn(
    "The current project does not match your configured GitLab instance.",
  )
  val noInstance = GitLabProjectUrlResolver.Resolution.Warn("Set your GitLab instance URL in the GitLab preferences.")

  fun store(url: String): ScopedPreferenceStore = mockk {
    every { getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns url
  }

  fun lookup(result: AssignedProjectLookup.Result): () -> AssignedProjectLookup = {
    mockk { every { forRepository(any()) } returns result }
  }

  fun resolver(
    url: String = "https://gitlab.com",
    assigned: AssignedProjectLookup.Result = AssignedProjectLookup.Result.None,
  ) = GitLabProjectUrlResolver(store(url), lookup(assigned))

  // A repo with an origin remote whose single commit holds [fileName], checked out on the local
  // branch [branch] (null keeps the default branch). [configure] runs on the open Git afterwards.
  fun tempRepo(
    fileName: String = "src/a.kt",
    remote: String = "git@gitlab.com:group/proj.git",
    commit: Boolean = true,
    branch: String? = "topic",
    configure: (Git) -> Unit = {},
  ): Pair<File, File> {
    val dir = Files.createTempDirectory("nav03-repo").toFile()
    createdTempPaths += dir
    Git.init().setDirectory(dir).call().use { git ->
      git.repository.config.apply {
        setString("remote", "origin", "url", remote)
        save()
      }
      val f = File(dir, fileName).apply {
        parentFile.mkdirs()
        writeText("hello")
      }
      if (commit) {
        git.add().addFilepattern(".").call()
        git.commit().setMessage("init").setAuthor("t", "t@e").setCommitter("t", "t@e").call()
        if (branch != null) git.checkout().setCreateBranch(true).setName(branch).call()
      }
      configure(git)
      return dir to f
    }
  }

  fun Git.track(branch: String, remote: String, merge: String) {
    repository.config.apply {
      setString("branch", branch, "remote", remote)
      setString("branch", branch, "merge", merge)
      save()
    }
  }

  afterSpec { createdTempPaths.forEach { path -> path.deleteRecursively() } }

  describe("resolveBranchBlobUrl") {
    it("uses the tracking branch on the project's remote, encoded as one segment, with the line range") {
      val (_, file) = tempRepo { it.track("topic", "origin", "refs/heads/feature/x") }

      resolver().resolveBranchBlobUrl(file, 1, 3) shouldBe
        GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.com/group/proj/-/blob/feature%2Fx/src/a.kt#L2-4")
    }

    it("uses the local branch name when nothing is tracked (unpushed branches are not checked)") {
      val (_, file) = tempRepo()

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe
        GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.com/group/proj/-/blob/topic/src/a.kt")
    }

    it("uses the local branch name when the tracking branch is on another remote") {
      val (_, file) = tempRepo { it.track("topic", "upstream", "refs/heads/upstream-name") }

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe
        GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.com/group/proj/-/blob/topic/src/a.kt")
    }

    it("matches the tracking remote against an assigned project's remote name") {
      val (dir, file) = tempRepo { it.track("topic", "upstream", "refs/heads/upstream-name") }
      val other = "https://gitlab.example.com"
      val assigned = GitLabProjectInfo(dir, dir, "other/proj", other, "$other/other/proj", "upstream")

      resolver(assigned = AssignedProjectLookup.Result.Use(assigned)).resolveBranchBlobUrl(file, null, null) shouldBe
        GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.example.com/other/proj/-/blob/upstream-name/src/a.kt")
    }

    it("anchors a caret without a range to its single line") {
      val (_, file) = tempRepo()

      resolver().resolveBranchBlobUrl(file, 6, 6) shouldBe
        GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.com/group/proj/-/blob/topic/src/a.kt#L7")
    }

    it("encodes spaces and # in the file path segment by segment") {
      val (_, file) = tempRepo(fileName = "dir a/b #c.txt")

      resolver().resolveBranchBlobUrl(file, 0, 0) shouldBe
        GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.com/group/proj/-/blob/topic/dir%20a/b%20%23c.txt#L1")
    }

    it("warns DETACHED_HEAD instead of building a link on a detached HEAD") {
      val (_, file) = tempRepo {
        it.track("topic", "origin", "refs/heads/topic")
        it.checkout().setName(it.repository.resolve("HEAD")!!.name).call()
      }

      resolver().resolveBranchBlobUrl(file, 0, 0) shouldBe detached
    }

    it("warns NOT_COMMITTED for a repository without commits") {
      val (_, file) = tempRepo(commit = false)

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe notCommitted
    }

    it("warns NOT_COMMITTED for a file that was never committed in a repository with commits") {
      val (dir, _) = tempRepo()
      val untracked = File(dir, "untracked.txt").apply { writeText("never added") }

      resolver().resolveBranchBlobUrl(untracked, null, null) shouldBe notCommitted
    }

    it("checks the commit before the project, like NAV-02") {
      val (_, file) = tempRepo(commit = false)

      resolver(url = "").resolveBranchBlobUrl(file, null, null) shouldBe notCommitted
    }

    it("checks the project before the detached HEAD") {
      val (_, file) = tempRepo(remote = "/srv/git/mirror.git") {
        it.checkout().setName(it.repository.resolve("HEAD")!!.name).call()
      }

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe noRemote
    }

    it("warns NO_REMOTE when no remote is GitLab-shaped") {
      val (_, file) = tempRepo(remote = "/srv/git/mirror.git")

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe noRemote
    }

    it("warns MISMATCH when the remote is on another host") {
      val (_, file) = tempRepo(remote = "git@other.com:group/proj.git")

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe mismatch
    }

    it("warns NO_INSTANCE when no instance URL is configured") {
      val (_, file) = tempRepo()

      resolver(url = "").resolveBranchBlobUrl(file, null, null) shouldBe noInstance
    }

    it("passes on an assignment's warning") {
      val (_, file) = tempRepo()

      resolver(assigned = AssignedProjectLookup.Result.Warn("assignment is stale"))
        .resolveBranchBlobUrl(file, null, null) shouldBe GitLabProjectUrlResolver.Resolution.Warn("assignment is stale")
    }

    it("warns NOT_IN_REPO for a file outside any repository") {
      val loose = Files.createTempFile("loose", ".txt").toFile()
      createdTempPaths += loose

      resolver().resolveBranchBlobUrl(loose, null, null) shouldBe notInRepo
    }

    it("warns NOT_IN_REPO for a file next to a git dir whose core.worktree points elsewhere") {
      // findGitDir walks up from the file, but JGit takes the work tree from core.worktree, so the
      // file lies outside it and relativizes to "../...".
      val elsewhere = Files.createTempDirectory("nav03-worktree").toFile()
      createdTempPaths += elsewhere
      val (_, file) = tempRepo(fileName = "a.txt") { git ->
        git.repository.config.apply {
          setString("core", null, "worktree", elsewhere.absolutePath)
          save()
        }
      }

      resolver().resolveBranchBlobUrl(file, null, null) shouldBe notInRepo
    }

    it("warns NOT_IN_REPO instead of throwing for a bare repository") {
      val dir = Files.createTempDirectory("nav03-bare").toFile()
      createdTempPaths += dir
      Git.init().setBare(true).setDirectory(dir).call().use { git ->
        git.repository.config.apply {
          setString("remote", "origin", "url", "git@gitlab.com:group/proj.git")
          save()
        }
      }

      resolver().resolveBranchBlobUrl(File(dir, "x.txt"), null, null) shouldBe notInRepo
    }

    it("maps a failure reading the branch to NOT_IN_REPO, not to DETACHED_HEAD") {
      val (_, file) = tempRepo()
      val failingBranch: (Repository) -> CurrentBranch = { throw IllegalStateException("config unreadable") }
      val resolver = GitLabProjectUrlResolver(
        store("https://gitlab.com"),
        lookup(AssignedProjectLookup.Result.None),
        failingBranch,
      )

      resolver.resolveBranchBlobUrl(file, null, null) shouldBe notInRepo
    }
  }
})
