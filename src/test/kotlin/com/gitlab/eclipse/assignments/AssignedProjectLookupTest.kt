package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.GitAuthConfigurer
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.File
import kotlin.io.path.createTempDirectory

/** A real repository: the lookup reads the remotes from git itself. */
class AssignedProjectLookupTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val instanceUrl = "https://gitlab.com"

  fun newRepo(remoteUrl: String? = "https://gitlab.com/g/p.git"): File {
    val dir = createTempDirectory("assigned-lookup").toFile()
    Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
      if (remoteUrl != null) {
        git.remoteAdd().setName("origin").setUri(URIish(remoteUrl)).call()
      }
    }
    return dir
  }

  fun preferences(): ScopedPreferenceStore = mockk<ScopedPreferenceStore>().also {
    every { it.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns instanceUrl
  }

  fun store(assignment: ProjectAssignment?): SelectedProjectStore = mockk<SelectedProjectStore>().also {
    every { it.isEmpty() } returns (assignment == null)
    every { it.find(any()) } returns assignment
  }

  fun validator(): AssignmentValidator {
    val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns "t" }
    return AssignmentValidator(GitAuthConfigurer(tokenManager))
  }

  fun lookup(assignment: ProjectAssignment?) =
    AssignedProjectLookup(store(assignment), validator(), preferences())

  fun assignment(dir: File, remoteUrl: String = "https://gitlab.com/g/p.git", instance: String = instanceUrl) =
    ProjectAssignment(
      repositoryRootPath = dir.canonicalPath,
      remoteUrl = remoteUrl,
      instanceUrl = instance,
      namespaceWithPath = "assigned/project",
      projectId = 7,
    )

  fun open(dir: File) = FileRepositoryBuilder().setGitDir(File(dir, ".git")).build()

  describe("forRepository") {
    it("returns None without reading the repository when nothing is assigned (A8)") {
      val dir = newRepo()
      val emptyStore = mockk<SelectedProjectStore>()
      every { emptyStore.isEmpty() } returns true
      val lookup = AssignedProjectLookup(emptyStore, validator(), preferences())

      open(dir).use { lookup.forRepository(it) } shouldBe AssignedProjectLookup.Result.None
      // find() is never consulted: the emptiness check alone decides.
      io.mockk.verify(exactly = 0) { emptyStore.find(any()) }
    }

    it("returns None when this repository has no assignment") {
      val dir = newRepo()

      open(dir).use { lookup(null).forRepository(it) } shouldBe AssignedProjectLookup.Result.None
    }

    it("uses the assigned project, overriding what the remote says") {
      val dir = newRepo()

      val result = open(dir).use { lookup(assignment(dir)).forRepository(it) }

      result.shouldBeInstanceOf<AssignedProjectLookup.Result.Use>()
      result.project.namespaceWithPath shouldBe "assigned/project"
      result.project.webUrl shouldBe "https://gitlab.com/assigned/project"
      result.project.remoteName shouldBe "origin"
      result.project.workTree.canonicalPath shouldBe dir.canonicalPath
    }

    it("warns instead of using an assignment made against another instance (A14)") {
      val dir = newRepo()

      val result = open(dir).use {
        lookup(assignment(dir, instance = "https://other.example")).forRepository(it)
      }

      result.shouldBeInstanceOf<AssignedProjectLookup.Result.Warn>()
      result.message.contains("different instance") shouldBe true
    }

    it("warns instead of using an assignment whose remote is gone") {
      val dir = newRepo(remoteUrl = null)

      val result = open(dir).use { lookup(assignment(dir)).forRepository(it) }

      result.shouldBeInstanceOf<AssignedProjectLookup.Result.Warn>()
      result.message.contains("no longer exists") shouldBe true
    }

    it("warns instead of using an assignment whose remote is not on that instance") {
      val dir = newRepo(remoteUrl = "https://github.com/g/p.git")

      val result = open(dir).use {
        lookup(assignment(dir, remoteUrl = "https://github.com/g/p.git")).forRepository(it)
      }

      result.shouldBeInstanceOf<AssignedProjectLookup.Result.Warn>()
      result.message.contains("not on that instance") shouldBe true
    }
  }
})
