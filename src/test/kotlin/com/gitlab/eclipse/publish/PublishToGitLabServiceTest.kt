package com.gitlab.eclipse.publish

import com.gitlab.eclipse.api.CreatedProject
import com.gitlab.eclipse.api.ProjectCreationService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.BranchPushService
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.mergerequests.PushOutcome
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory

/** Real repositories for the git half; the REST and push collaborators are stubbed. */
class PublishToGitLabServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val instanceUrl = "https://gitlab.com"
  val created = CreatedProject(
    id = 9,
    sshUrl = "git@gitlab.com:g/p.git",
    httpUrl = "https://gitlab.com/g/p.git",
    webUrl = "https://gitlab.com/g/p",
  )

  fun newDir(): File = createTempDirectory("publish-service").toFile()

  fun dirWithFiles(): File = newDir().also { File(it, "a.txt").writeText("a") }

  /** An in-memory record store: the real one needs a preference store. */
  fun recordStore(): PublishRecordStore {
    val store = mockk<PublishRecordStore>()
    val held = mutableMapOf<String, PublishRecord>()
    every { store.find(any()) } answers { held[firstArg()] }
    every { store.put(any()) } answers {
      val record = firstArg<PublishRecord>()
      held[record.repositoryRootPath] = record
      true
    }
    every { store.remove(any()) } answers {
      held.remove(firstArg<String>())
      true
    }
    return store
  }

  fun creation(): ProjectCreationService = mockk<ProjectCreationService>().also {
    every { it.resolveNamespaceId(any(), any()) } returns 7L
    every { it.createProject(any(), any(), any(), any()) } returns created
  }

  fun pusher(outcome: PushOutcome = PushOutcome.Ok): BranchPushService =
    mockk<BranchPushService>().also { every { it.push(any(), any()) } returns outcome }

  fun request(folder: File, useSsh: Boolean = false) = PublishRequest(
    folder = folder,
    namespacePath = "g",
    projectPath = "p",
    visibility = "private",
    useSsh = useSsh,
  )

  describe("publish") {
    it("initialises the folder, creates the project, adds a remote and pushes") {
      val dir = dirWithFiles()
      val service = PublishToGitLabService(GitOperationGuard(), recordStore(), creation(), pusher())

      val outcome = service.publish(request(dir), instanceUrl)

      outcome shouldBe PublishOutcome.Published("https://gitlab.com/g/p")
      Git.open(dir).use { git ->
        git.log().call().single().fullMessage shouldBe "Initial commit"
        git.repository.config.getString("remote", "origin", "url") shouldBe "https://gitlab.com/g/p.git"
      }
    }

    it("uses the ssh url when the user picked ssh") {
      val dir = dirWithFiles()
      val service = PublishToGitLabService(GitOperationGuard(), recordStore(), creation(), pusher())

      service.publish(request(dir, useSsh = true), instanceUrl)

      Git.open(dir).use {
        it.repository.config.getString("remote", "origin", "url") shouldBe "git@gitlab.com:g/p.git"
      }
    }

    it("honours .gitignore in the initial commit") {
      val dir = dirWithFiles()
      File(dir, ".gitignore").writeText("secret.txt\n")
      File(dir, "secret.txt").writeText("token")
      val service = PublishToGitLabService(GitOperationGuard(), recordStore(), creation(), pusher())

      service.publish(request(dir), instanceUrl)

      Git.open(dir).use { git ->
        val committed = git.repository.readDirCache()
        (0 until committed.entryCount).map { committed.getEntry(it).pathString } shouldBe
          listOf(".gitignore", "a.txt")
      }
    }

    it("records the intent before creating the project") {
      val dir = dirWithFiles()
      val store = recordStore()
      val creator = creation()
      PublishToGitLabService(GitOperationGuard(), store, creator, pusher()).publish(request(dir), instanceUrl)

      verify(ordering = io.mockk.Ordering.ORDERED) {
        store.put(match { it is PublishRecord.Intent })
        creator.createProject(any(), any(), any(), any())
        store.put(match { it is PublishRecord.State })
      }
    }

    it("does not create a project when the intent cannot be persisted") {
      val dir = dirWithFiles()
      val store = mockk<PublishRecordStore>()
      every { store.find(any()) } returns null
      every { store.put(any()) } returns false
      val creator = creation()

      val outcome = PublishToGitLabService(GitOperationGuard(), store, creator, pusher())
        .publish(request(dir), instanceUrl)

      outcome shouldBe PublishOutcome.RecordPersistenceFailed
      verify(exactly = 0) { creator.createProject(any(), any(), any(), any()) }
    }

    it("drops the record once the push has landed") {
      val dir = dirWithFiles()
      val store = recordStore()

      PublishToGitLabService(GitOperationGuard(), store, creation(), pusher())
        .publish(request(dir), instanceUrl)

      store.find(dir.canonicalPath) shouldBe null
    }

    it("keeps the project, the remote and the record when the push is rejected") {
      val dir = dirWithFiles()
      val store = recordStore()
      val rejected = pusher(PushOutcome.Rejected(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD))

      val outcome = PublishToGitLabService(GitOperationGuard(), store, creation(), rejected)
        .publish(request(dir), instanceUrl)

      outcome shouldBe PublishOutcome.PushFailed(
        "https://gitlab.com/g/p",
        "https://gitlab.com/g/p.git",
        rejected = true,
      )
      store.find(dir.canonicalPath).shouldBeInstanceOf<PublishRecord.State>()
      Git.open(dir).use {
        it.repository.config.getString("remote", "origin", "url") shouldBe "https://gitlab.com/g/p.git"
      }
    }

    it("omits the namespace lookup for the personal namespace") {
      val dir = dirWithFiles()
      val creator = creation()

      PublishToGitLabService(GitOperationGuard(), recordStore(), creator, pusher())
        .publish(request(dir).copy(namespacePath = ""), instanceUrl)

      verify(exactly = 0) { creator.resolveNamespaceId(any(), any()) }
      verify { creator.createProject("p", null, "private", any()) }
    }

    it("reports Busy without touching the folder when the repository is held") {
      val dir = dirWithFiles()
      val guard = GitOperationGuard()
      val service = PublishToGitLabService(guard, recordStore(), creation(), pusher())

      val outcome = guard.withRepo(File(dir, ".git").path) {
        service.publish(request(dir), instanceUrl)
      }

      outcome shouldBe PublishOutcome.Busy
      File(dir, ".git").exists() shouldBe false
    }

    it("stops without creating anything when the connection gate rejects the instance") {
      val dir = dirWithFiles()
      val creator = mockk<ProjectCreationService>()
      every { creator.resolveNamespaceId(any(), any()) } returns null

      val outcome = PublishToGitLabService(GitOperationGuard(), recordStore(), creator, pusher())
        .publish(request(dir), instanceUrl)

      outcome shouldBe PublishOutcome.InstanceMismatch
    }

    it("names the second remote origin2, skipping origin1") {
      val dir = dirWithFiles()
      Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
        File(dir, "a.txt").writeText("a")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("m").setAuthor("t", "t@e.com").setSign(false).call()
        git.remoteAdd().setName("origin")
          .setUri(org.eclipse.jgit.transport.URIish("https://elsewhere/x/y.git")).call()
      }

      PublishToGitLabService(GitOperationGuard(), recordStore(), creation(), pusher())
        .publish(request(dir), instanceUrl)

      Git.open(dir).use {
        it.repository.config.getString("remote", "origin2", "url") shouldBe "https://gitlab.com/g/p.git"
      }
    }
  }

  describe("resumePush") {
    val state = PublishRecord.State(
      repositoryRootPath = "",
      instanceUrl = "https://gitlab.com",
      namespacePath = "g",
      projectPath = "p",
      projectId = 9,
      normalizedRemoteUrl = "https://gitlab.com/g/p",
      remoteName = "origin",
      projectWebUrl = "https://gitlab.com/g/p",
    )

    it("re-adds the recorded remote and pushes without creating a project (A24)") {
      val dir = dirWithFiles()
      Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("m").setAuthor("t", "t@e.com").setSign(false).call()
      }
      val creator = creation()

      val outcome = PublishToGitLabService(GitOperationGuard(), recordStore(), creator, pusher())
        .resumePush(state.copy(repositoryRootPath = dir.canonicalPath), dir, instanceUrl)

      outcome shouldBe PublishOutcome.Published("https://gitlab.com/g/p")
      verify(exactly = 0) { creator.createProject(any(), any(), any(), any()) }
      Git.open(dir).use {
        it.repository.config.getString("remote", "origin", "url") shouldBe "https://gitlab.com/g/p"
      }
    }

    it("leaves an already present remote alone") {
      val dir = dirWithFiles()
      Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
        git.add().addFilepattern(".").call()
        git.commit().setMessage("m").setAuthor("t", "t@e.com").setSign(false).call()
        git.remoteAdd().setName("origin")
          .setUri(org.eclipse.jgit.transport.URIish("https://gitlab.com/g/p.git")).call()
      }

      PublishToGitLabService(GitOperationGuard(), recordStore(), creation(), pusher())
        .resumePush(state.copy(repositoryRootPath = dir.canonicalPath), dir, instanceUrl)

      Git.open(dir).use {
        it.repository.config.getString("remote", "origin", "url") shouldBe "https://gitlab.com/g/p.git"
      }
    }
  }
})
