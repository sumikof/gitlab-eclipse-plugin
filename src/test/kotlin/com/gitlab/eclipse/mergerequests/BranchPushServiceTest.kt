package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.nio.file.Files

/**
 * Exercises the real JGit push path against a LOCAL bare "remote" repository (a plain
 * file-path remote needs no network and no credentials — [GitAuthConfigurer] is constructed
 * with a mock token manager and never consulted for non-HTTP remotes). Auth against a real
 * GitLab host (HTTPS token / SSH) and server-side rejections such as protected branches are
 * real-machine-only and not covered here.
 */
class BranchPushServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  // Every temp dir created below is registered here and wiped in afterSpec (mirrors
  // MrBranchCheckoutServiceTest's temp-dir cleanup pattern).
  val createdTempPaths = mutableListOf<File>()

  afterSpec { createdTempPaths.forEach { it.deleteRecursively() } }

  fun tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { createdTempPaths += it }

  fun commit(git: Git, dir: File, fileName: String, content: String): RevCommit {
    File(dir, fileName).writeText(content)
    git.add().addFilepattern(".").call()
    return git.commit().setMessage("add $fileName").setAuthor("t", "t@e").setCommitter("t", "t@e").call()
  }

  fun service(): BranchPushService {
    val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns "" }
    return BranchPushService(GitOperationGuard(), GitAuthConfigurer(tokenManager))
  }

  /** A bare "remote" plus a local repository with `origin` pointing at it and `master` at
   *  [c1][PushFixture.c1] (not yet pushed). */
  data class PushFixture(
    val remoteGit: Git,
    val remoteDir: File,
    val localGit: Git,
    val localDir: File,
    val c1: RevCommit,
    val context: RepositoryContext,
  )

  fun pushFixture(): PushFixture {
    val remoteDir = tempDir("push-remote")
    val remoteGit = Git.init().setBare(true).setInitialBranch("master").setDirectory(remoteDir).call()
    val localDir = tempDir("push-local")
    val localGit = Git.init().setInitialBranch("master").setDirectory(localDir).call()
    val config = localGit.repository.config
    config.setString("remote", "origin", "url", remoteDir.toURI().toString())
    config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
    config.save()
    val c1 = commit(localGit, localDir, "a.txt", "one")
    val context = RepositoryContext(
      gitDir = localGit.repository.directory.canonicalPath,
      workTree = localDir.canonicalPath,
      namespaceWithPath = "g/p",
      instanceUrl = "https://gitlab.example.com",
      webUrl = "https://gitlab.example.com/g/p",
      remoteName = "origin",
      projectId = "g%2Fp",
    )
    return PushFixture(remoteGit, remoteDir, localGit, localDir, c1, context)
  }

  describe("push") {
    it("pushes a fresh branch to the bare remote and sets upstream config (happy path)") {
      val fixture = pushFixture()

      val outcome = service().push(fixture.context, "master")

      outcome shouldBe PushOutcome.Ok
      fixture.remoteGit.repository.resolve("refs/heads/master")?.name shouldBe fixture.c1.name
      val config = fixture.localGit.repository.config
      config.getString("branch", "master", "remote") shouldBe "origin"
      config.getString("branch", "master", "merge") shouldBe "refs/heads/master"
    }

    it("returns Ok when the remote branch is already up to date") {
      val fixture = pushFixture()
      service().push(fixture.context, "master") shouldBe PushOutcome.Ok

      val outcome = service().push(fixture.context, "master")

      outcome shouldBe PushOutcome.Ok
      fixture.remoteGit.repository.resolve("refs/heads/master")?.name shouldBe fixture.c1.name
    }

    it("returns Rejected on a non-fast-forward push, leaves the remote and upstream untouched") {
      val fixture = pushFixture()
      // Remote master ends up at c2; the local branch is then rewound to c1 and given a
      // divergent commit c3, so pushing c3 is a non-fast-forward.
      val c2 = commit(fixture.localGit, fixture.localDir, "b.txt", "two")
      service().push(fixture.context, "master") shouldBe PushOutcome.Ok
      fixture.localGit.reset().setMode(ResetCommand.ResetType.HARD).setRef(fixture.c1.name).call()
      commit(fixture.localGit, fixture.localDir, "c.txt", "three")
      // Drop the upstream config the successful push wrote, so this test can prove the
      // rejected push does not (re)write it.
      val config = fixture.localGit.repository.config
      config.unsetSection("branch", "master")
      config.save()

      val outcome = service().push(fixture.context, "master")

      outcome shouldBe PushOutcome.Rejected(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD)
      fixture.remoteGit.repository.resolve("refs/heads/master")?.name shouldBe c2.name
      config.getString("branch", "master", "remote").shouldBeNull()
      config.getString("branch", "master", "merge").shouldBeNull()
    }

    it("returns Busy when another operation already holds the repository guard") {
      val fixture = pushFixture()
      val guard = GitOperationGuard()
      val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns "" }
      val busyService = BranchPushService(guard, GitAuthConfigurer(tokenManager))

      val outcome = guard.withRepo(fixture.context.gitDir) {
        busyService.push(fixture.context, "master")
      }

      outcome shouldBe PushOutcome.Busy
      fixture.remoteGit.repository.resolve("refs/heads/master").shouldBeNull()
    }

    it("pushes to remote.<name>.pushurl, not the fetch url (per-transport auth does not break it)") {
      val fixture = pushFixture()
      // Fetch url points nowhere; the real bare remote is the pushurl. JGit pushes only to the
      // pushurl, and a successful push proves the per-transport auth (GitAuthConfigurer scopes
      // credentials off each transport's own URI) leaves this file: destination working.
      val config = fixture.localGit.repository.config
      config.setString("remote", "origin", "url", "https://gitlab.invalid/nonexistent.git")
      config.setString("remote", "origin", "pushurl", fixture.remoteDir.toURI().toString())
      config.save()

      val outcome = service().push(fixture.context, "master")

      outcome shouldBe PushOutcome.Ok
      fixture.remoteGit.repository.resolve("refs/heads/master")?.name shouldBe fixture.c1.name
    }

    it("returns Rejected when a later push destination rejects even though the first accepts") {
      val fixture = pushFixture()
      // A second bare remote whose master already holds an UNRELATED commit, so pushing the local
      // history to it is a non-fast-forward while the first (empty) remote accepts the same push.
      val remoteBDir = tempDir("push-remote-b")
      Git.init().setBare(true).setInitialBranch("master").setDirectory(remoteBDir).call()
      val seedDir = tempDir("push-seed")
      val seedGit = Git.init().setInitialBranch("master").setDirectory(seedDir).call()
      seedGit.repository.config.apply {
        setString("remote", "b", "url", remoteBDir.toURI().toString())
        save()
      }
      commit(seedGit, seedDir, "unrelated.txt", "x")
      seedGit.push().setRemote("b").setRefSpecs(RefSpec("refs/heads/master:refs/heads/master")).call()

      // origin pushes to BOTH the empty remote (accepts) and the seeded remote (rejects).
      val config = fixture.localGit.repository.config
      config.setStringList(
        "remote",
        "origin",
        "pushurl",
        listOf(fixture.remoteDir.toURI().toString(), remoteBDir.toURI().toString()),
      )
      config.save()

      val outcome = service().push(fixture.context, "master")

      outcome.shouldBeInstanceOf<PushOutcome.Rejected>()
      // Upstream config must NOT be written on a partial failure.
      config.getString("branch", "master", "remote").shouldBeNull()
    }

    it("never throws: a bogus git directory maps to Failed") {
      val bogusDir = File(tempDir("push-bogus"), "missing")
      val context = RepositoryContext(
        gitDir = bogusDir.absolutePath,
        workTree = bogusDir.absolutePath,
        namespaceWithPath = "g/p",
        instanceUrl = "https://gitlab.example.com",
        webUrl = "https://gitlab.example.com/g/p",
        remoteName = "origin",
        projectId = "g%2Fp",
      )

      service().push(context, "master").shouldBeInstanceOf<PushOutcome.Failed>()
    }
  }

  describe("PushOutcome.Failed.toString") {
    it("keeps the cause's message out and its type in") {
      "${PushOutcome.Failed(java.io.IOException("/home/user/secret-repo not found"))}" shouldBe
        "PushOutcome.Failed(type=java.io.IOException)"
    }

    // A2(a): 例外型は「秘匿値の許された投影」。型を変えたら出力も変わる。
    // これが無いと toString を固定の定数にしても通る(設計 §22.1 の (v) 群)。
    it("reflects a different cause type") {
      "${PushOutcome.Failed(IllegalStateException("/home/user/secret-repo not found"))}" shouldBe
        "PushOutcome.Failed(type=java.lang.IllegalStateException)"
    }

    it("says so when it carries no cause at all") {
      "${PushOutcome.Failed(null)}" shouldBe "PushOutcome.Failed(type=null)"
    }
  }
})
