package com.gitlab.eclipse.publish

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory

/** Real repositories: the whole judgement is about what git actually holds. */
class PublishPreflightTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val instanceUrl = "https://gitlab.com"

  fun newDir(): File = createTempDirectory("publish-preflight").toFile()

  fun newRepo(dir: File): Git = Git.init().setDirectory(dir).setInitialBranch("main").call()

  fun commit(git: Git, dir: File, name: String, text: String) {
    File(dir, name).writeText(text)
    git.add().addFilepattern(name).call()
    git.commit().setMessage("m").setAuthor("t", "t@example.com").setSign(false).call()
  }

  fun records(record: PublishRecord? = null): PublishRecordStore = mockk<PublishRecordStore>().also {
    every { it.find(any()) } returns record
  }

  fun state(remoteUrl: String = "https://gitlab.com/g/p", instance: String = instanceUrl) =
    PublishRecord.State(
      repositoryRootPath = "",
      instanceUrl = instance,
      namespacePath = "g",
      projectPath = "p",
      projectId = 1,
      normalizedRemoteUrl = remoteUrl,
      remoteName = "origin",
      projectWebUrl = "https://gitlab.com/g/p",
    )

  describe("with no record") {
    it("treats a plain directory holding files as publishable") {
      val dir = newDir()
      File(dir, "a.txt").writeText("a")
      File(dir, "b.txt").writeText("b")

      val result = PublishPreflight(records()).inspect(dir, instanceUrl)

      result.shouldBeInstanceOf<Preflight.Fresh>()
      result.isRepository shouldBe false
      result.trackableFileCount shouldBe 2
      result.hasGitignore shouldBe false
    }

    it("reports the presence of a .gitignore so the caller can drop the warning") {
      val dir = newDir()
      File(dir, "a.txt").writeText("a")
      File(dir, ".gitignore").writeText("*.log\n")

      val result = PublishPreflight(records()).inspect(dir, instanceUrl)

      result.shouldBeInstanceOf<Preflight.Fresh>()
      result.hasGitignore shouldBe true
    }

    it("stops on an existing remote, which this command did not create") {
      val dir = newDir()
      newRepo(dir).use {
        commit(it, dir, "a.txt", "a")
        it.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish("https://h/g/p.git")).call()
      }

      PublishPreflight(records()).inspect(dir, instanceUrl) shouldBe
        Preflight.Blocked(Preflight.Stop.REMOTE_EXISTS)
    }

    it("stops on a detached HEAD, which gives no branch to push") {
      val dir = newDir()
      newRepo(dir).use { git ->
        commit(git, dir, "a.txt", "a")
        git.checkout().setName(git.repository.resolve("HEAD").name).call()
      }

      PublishPreflight(records()).inspect(dir, instanceUrl) shouldBe
        Preflight.Blocked(Preflight.Stop.DETACHED_HEAD)
    }

    it("stops on an empty directory") {
      PublishPreflight(records()).inspect(newDir(), instanceUrl) shouldBe
        Preflight.Blocked(Preflight.Stop.NO_FILES)
    }

    it("stops when the target is not a directory") {
      val file = File(newDir(), "a.txt").apply { writeText("a") }

      PublishPreflight(records()).inspect(file, instanceUrl) shouldBe
        Preflight.Blocked(Preflight.Stop.NOT_A_DIRECTORY)
    }

    it("does not count .git contents as trackable files") {
      val dir = newDir()
      newRepo(dir).use { commit(it, dir, "a.txt", "a") }

      val result = PublishPreflight(records()).inspect(dir, instanceUrl)

      result.shouldBeInstanceOf<Preflight.Fresh>()
      result.isRepository shouldBe true
      result.trackableFileCount shouldBe 1
    }

    it("creates nothing while inspecting") {
      val dir = newDir()
      File(dir, "a.txt").writeText("a")

      PublishPreflight(records()).inspect(dir, instanceUrl)

      File(dir, ".git").exists() shouldBe false
    }
  }

  describe("with a confirmed state") {
    it("resumes the push when the recorded remote is present") {
      val dir = newDir()
      newRepo(dir).use {
        commit(it, dir, "a.txt", "a")
        it.remoteAdd().setName("origin")
          .setUri(org.eclipse.jgit.transport.URIish("https://gitlab.com/g/p.git")).call()
      }

      val result = PublishPreflight(records(state())).inspect(dir, instanceUrl)

      result.shouldBeInstanceOf<Preflight.ResumePush>()
      result.remoteMissing shouldBe false
    }

    it("resumes the push when the recorded remote is gone, without creating a project (A24)") {
      val dir = newDir()
      newRepo(dir).use { commit(it, dir, "a.txt", "a") }

      val result = PublishPreflight(records(state())).inspect(dir, instanceUrl)

      result.shouldBeInstanceOf<Preflight.ResumePush>()
      result.remoteMissing shouldBe true
    }

    it("stops when a remote exists but is not the recorded one") {
      val dir = newDir()
      newRepo(dir).use {
        commit(it, dir, "a.txt", "a")
        it.remoteAdd().setName("origin")
          .setUri(org.eclipse.jgit.transport.URIish("https://elsewhere/x/y.git")).call()
      }

      PublishPreflight(records(state())).inspect(dir, instanceUrl) shouldBe
        Preflight.Blocked(Preflight.Stop.REMOTE_EXISTS)
    }

    it("stops when the record belongs to another instance") {
      val dir = newDir()
      newRepo(dir).use { commit(it, dir, "a.txt", "a") }

      PublishPreflight(records(state(instance = "https://other.example")))
        .inspect(dir, instanceUrl) shouldBe Preflight.Blocked(Preflight.Stop.INSTANCE_MISMATCH)
    }

    it("matches the remote through url normalisation, not by literal equality") {
      val dir = newDir()
      newRepo(dir).use {
        commit(it, dir, "a.txt", "a")
        // Recorded normalised as https://gitlab.com/g/p; the config holds the .git form.
        it.remoteAdd().setName("origin")
          .setUri(org.eclipse.jgit.transport.URIish("https://GitLab.com/g/p.git")).call()
      }

      val result = PublishPreflight(records(state())).inspect(dir, instanceUrl)

      result.shouldBeInstanceOf<Preflight.ResumePush>()
      result.remoteMissing shouldBe false
    }
  }

  describe("with an unconfirmed intent") {
    val intent = PublishRecord.Intent(
      repositoryRootPath = "",
      instanceUrl = instanceUrl,
      namespacePath = "g",
      projectPath = "p",
      recordedAt = Instant.ofEpochMilli(1_700_000_000_000),
    )

    it("hands it to the recovery protocol") {
      val dir = newDir()
      newRepo(dir).use { commit(it, dir, "a.txt", "a") }

      PublishPreflight(records(intent)).inspect(dir, instanceUrl) shouldBe
        Preflight.RecoverIntent(intent)
    }

    it("does not attempt recovery for another instance") {
      val dir = newDir()
      newRepo(dir).use { commit(it, dir, "a.txt", "a") }

      PublishPreflight(records(intent.copy(instanceUrl = "https://other.example")))
        .inspect(dir, instanceUrl) shouldBe Preflight.Blocked(Preflight.Stop.INSTANCE_MISMATCH)
    }

    it("is consulted before the remote check, so a state with no remote never runs the fresh flow") {
      val dir = newDir()
      newRepo(dir).use {
        commit(it, dir, "a.txt", "a")
        it.remoteAdd().setName("origin")
          .setUri(org.eclipse.jgit.transport.URIish("https://elsewhere/x/y.git")).call()
      }

      // An intent plus an unrelated remote: without the record-first ordering this would be
      // REMOTE_EXISTS and the recorded project would be orphaned (§9.6 R8-1).
      PublishPreflight(records(intent)).inspect(dir, instanceUrl) shouldBe
        Preflight.RecoverIntent(intent)
    }
  }
})
