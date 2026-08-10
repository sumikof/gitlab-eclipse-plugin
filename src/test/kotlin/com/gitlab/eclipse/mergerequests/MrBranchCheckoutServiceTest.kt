package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.nio.file.Files

/**
 * Exercises the real JGit fetch+checkout path against a LOCAL "remote" repository (a plain
 * file-path remote needs no network and no credentials — [GitAuthConfigurer] is constructed
 * with a mock token manager and never consulted for non-HTTP remotes). Auth against a real
 * GitLab host (HTTPS token / SSH) is real-machine-only and not covered here.
 */
class MrBranchCheckoutServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  // Every temp dir created below is registered here and wiped in afterSpec (mirrors
  // CurrentBranchGitReaderTest's temp-dir cleanup pattern).
  val createdTempPaths = mutableListOf<File>()

  afterSpec { createdTempPaths.forEach { it.deleteRecursively() } }

  fun tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { createdTempPaths += it }

  fun commit(git: Git, dir: File, fileName: String, content: String): RevCommit {
    File(dir, fileName).writeText(content)
    git.add().addFilepattern(".").call()
    return git.commit().setMessage("add $fileName").setAuthor("t", "t@e").setCommitter("t", "t@e").call()
  }

  fun service(): MrBranchCheckoutService {
    val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns "" }
    return MrBranchCheckoutService(GitOperationGuard(), GitAuthConfigurer(tokenManager))
  }

  fun mr(sourceBranch: String?, sha: String?, sourceProjectId: Long? = 42, targetProjectId: Long? = 42) =
    GitLabMergeRequest(
      id = 1,
      iid = 7,
      title = "t",
      projectId = 42,
      webUrl = "https://gitlab.example.com/g/p/-/merge_requests/7",
      state = "opened",
      sourceProjectId = sourceProjectId,
      targetProjectId = targetProjectId,
      sourceBranch = sourceBranch,
      sha = sha,
    )

  /**
   * A local "remote" with `master` at [c1][RemoteFixture.c1], plus a clone of it taken at that
   * point. Tests then mutate the remote (new branch / advance master) to shape each scenario.
   */
  data class RemoteFixture(
    val remoteGit: Git,
    val remoteDir: File,
    val cloneGit: Git,
    val cloneGitDir: File,
    val c1: RevCommit,
    val context: RepositoryContext,
  )

  fun cloneFixture(): RemoteFixture {
    val remoteDir = tempDir("checkout-remote")
    val remoteGit = Git.init().setDirectory(remoteDir).setInitialBranch("master").call()
    val c1 = commit(remoteGit, remoteDir, "a.txt", "one")
    val cloneDir = tempDir("checkout-clone")
    val cloneGit = Git.cloneRepository()
      .setURI(remoteDir.toURI().toString())
      .setDirectory(cloneDir)
      .call()
    val gitDir = cloneGit.repository.directory.canonicalPath
    val context = RepositoryContext(
      gitDir = gitDir,
      workTree = cloneDir.canonicalPath,
      namespaceWithPath = "g/p",
      instanceUrl = "https://gitlab.example.com",
      webUrl = "https://gitlab.example.com/g/p",
      remoteName = "origin",
      projectId = "g%2Fp",
    )
    return RemoteFixture(remoteGit, remoteDir, cloneGit, File(gitDir), c1, context)
  }

  /** Creates branch `feature` in the remote AFTER the clone, so its remote-tracking ref only
   *  appears in the clone if the service actually fetched. */
  fun RemoteFixture.addRemoteFeatureBranch(): RevCommit {
    remoteGit.checkout().setCreateBranch(true).setName("feature").call()
    return commit(remoteGit, remoteDir, "f.txt", "feature")
  }

  describe("checkout") {
    it("rejects a cross-project (fork) merge request without touching the repository") {
      val fixture = cloneFixture()
      val featureTip = fixture.addRemoteFeatureBranch()

      val result = service().checkout(
        fixture.context,
        mr("feature", featureTip.name, sourceProjectId = 42, targetProjectId = 43),
      )

      result.shouldBeInstanceOf<CheckoutResult.Failed>()
      fixture.cloneGit.repository.resolve("refs/remotes/origin/feature").shouldBeNull()
      fixture.cloneGit.repository.branch shouldBe "master"
    }

    it("rejects a merge request whose sourceProjectId is null") {
      val fixture = cloneFixture()
      val featureTip = fixture.addRemoteFeatureBranch()

      val result = service().checkout(
        fixture.context,
        mr("feature", featureTip.name, sourceProjectId = null, targetProjectId = 42),
      )

      result.shouldBeInstanceOf<CheckoutResult.Failed>()
      fixture.cloneGit.repository.resolve("refs/remotes/origin/feature").shouldBeNull()
    }

    it("rejects a merge request with no source branch or no sha") {
      val fixture = cloneFixture()

      service().checkout(fixture.context, mr(null, fixture.c1.name))
        .shouldBeInstanceOf<CheckoutResult.Failed>()
      service().checkout(fixture.context, mr("feature", null))
        .shouldBeInstanceOf<CheckoutResult.Failed>()
    }

    it("returns StateBlocked when the repository is mid-merge, before fetching anything") {
      val fixture = cloneFixture()
      val featureTip = fixture.addRemoteFeatureBranch()
      // A MERGE_HEAD file puts the repository into a merging state (not SAFE).
      File(fixture.cloneGitDir, "MERGE_HEAD").writeText(fixture.c1.name + "\n")

      val result = service().checkout(fixture.context, mr("feature", featureTip.name))

      result shouldBe CheckoutResult.StateBlocked
      fixture.cloneGit.repository.resolve("refs/remotes/origin/feature").shouldBeNull()
      fixture.cloneGit.repository.resolve("HEAD")?.name shouldBe fixture.c1.name
    }

    it("returns Busy when another operation already holds the repository guard") {
      val fixture = cloneFixture()
      val featureTip = fixture.addRemoteFeatureBranch()
      val guard = GitOperationGuard()
      val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns "" }
      val busyService = MrBranchCheckoutService(guard, GitAuthConfigurer(tokenManager))

      val result = guard.withRepo(fixture.context.gitDir) {
        busyService.checkout(fixture.context, mr("feature", featureTip.name))
      }

      result shouldBe CheckoutResult.Busy
      fixture.cloneGit.repository.branch shouldBe "master"
    }

    it("returns OutOfSync with the fetched sha and does NOT check out when the MR sha is stale") {
      val fixture = cloneFixture()
      val featureTip = fixture.addRemoteFeatureBranch()
      // The MR record claims c1, but the remote branch has moved on to featureTip.
      val result = service().checkout(fixture.context, mr("feature", fixture.c1.name))

      result shouldBe CheckoutResult.OutOfSync(headSha = featureTip.name, checkedOut = false)
      fixture.cloneGit.repository.branch shouldBe "master"
      fixture.cloneGit.repository.resolve("refs/heads/feature").shouldBeNull()
      fixture.cloneGit.repository.resolve("HEAD")?.name shouldBe fixture.c1.name
    }

    it("creates a tracking local branch and checks it out when none exists (happy path)") {
      val fixture = cloneFixture()
      val featureTip = fixture.addRemoteFeatureBranch()

      val result = service().checkout(fixture.context, mr("feature", featureTip.name))

      result shouldBe CheckoutResult.Ok
      val repo = fixture.cloneGit.repository
      repo.branch shouldBe "feature"
      repo.resolve("HEAD")?.name shouldBe featureTip.name
      repo.config.getString("branch", "feature", "remote") shouldBe "origin"
      repo.config.getString("branch", "feature", "merge") shouldBe "refs/heads/feature"
    }

    it("switches to an existing local branch whose tip already equals the MR sha") {
      val fixture = cloneFixture()
      // Local master == remote master == c1; park HEAD elsewhere so the switch is observable.
      fixture.cloneGit.checkout().setCreateBranch(true).setName("elsewhere").call()

      val result = service().checkout(fixture.context, mr("master", fixture.c1.name))

      result shouldBe CheckoutResult.Ok
      fixture.cloneGit.repository.branch shouldBe "master"
      fixture.cloneGit.repository.resolve("HEAD")?.name shouldBe fixture.c1.name
    }

    it("fast-forwards an existing local branch that is strictly behind the MR sha") {
      val fixture = cloneFixture()
      // Advance the remote master past the clone's local master (c1 -> c2).
      val c2 = commit(fixture.remoteGit, fixture.remoteDir, "b.txt", "two")

      val result = service().checkout(fixture.context, mr("master", c2.name))

      result shouldBe CheckoutResult.Ok
      val repo = fixture.cloneGit.repository
      repo.branch shouldBe "master"
      repo.resolve("HEAD")?.name shouldBe c2.name
      repo.resolve("refs/heads/master")?.name shouldBe c2.name
      File(fixture.context.workTree, "b.txt").exists() shouldBe true
    }

    it("returns Diverged and leaves everything untouched when the local branch has its own commits") {
      val fixture = cloneFixture()
      // Local master gains its own commit while the remote master also advances: diverged.
      val localTip = commit(fixture.cloneGit, File(fixture.context.workTree), "local.txt", "local")
      val remoteTip = commit(fixture.remoteGit, fixture.remoteDir, "remote.txt", "remote")

      val result = service().checkout(fixture.context, mr("master", remoteTip.name))

      result shouldBe CheckoutResult.Diverged
      val repo = fixture.cloneGit.repository
      repo.branch shouldBe "master"
      repo.resolve("HEAD")?.name shouldBe localTip.name
      repo.resolve("refs/heads/master")?.name shouldBe localTip.name
    }

    it("never throws: a bogus git directory maps to Failed") {
      val bogusDir = File(tempDir("checkout-bogus"), "missing")
      val context = RepositoryContext(
        gitDir = bogusDir.absolutePath,
        workTree = bogusDir.absolutePath,
        namespaceWithPath = "g/p",
        instanceUrl = "https://gitlab.example.com",
        webUrl = "https://gitlab.example.com/g/p",
        remoteName = "origin",
        projectId = "g%2Fp",
      )

      val result = service().checkout(context, mr("feature", "0123456789012345678901234567890123456789"))

      result.shouldBeInstanceOf<CheckoutResult.Failed>()
    }
  }

  describe("CheckoutResult.Failed.toString") {
    it("keeps the cause's message out and its type in") {
      "${CheckoutResult.Failed(java.io.IOException("/home/user/secret-repo not found"))}" shouldBe
        "CheckoutResult.Failed(type=java.io.IOException)"
    }

    // A2(a): 例外型は「秘匿値の許された投影」。型を変えたら出力も変わる。
    // これが無いと toString を固定の定数にしても通る(設計 §22.1 の (v) 群)。
    it("reflects a different cause type") {
      "${CheckoutResult.Failed(IllegalStateException("/home/user/secret-repo not found"))}" shouldBe
        "CheckoutResult.Failed(type=java.lang.IllegalStateException)"
    }

    it("says so when it carries no cause at all") {
      "${CheckoutResult.Failed(null)}" shouldBe "CheckoutResult.Failed(type=null)"
    }
  }
})
