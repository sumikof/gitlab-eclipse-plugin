package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.GitAuthConfigurer
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AssignmentValidatorTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val instanceUrl = "https://gitlab.com"

  fun assignment(remoteUrl: String = "https://gitlab.com/g/p.git", instance: String = instanceUrl) =
    ProjectAssignment(
      repositoryRootPath = "/repo",
      remoteUrl = remoteUrl,
      instanceUrl = instance,
      namespaceWithPath = "g/p",
      projectId = 1,
    )

  /** The real host matching is exercised; only the token manager it never consults is mocked. */
  fun validator(): AssignmentValidator {
    val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns "t" }
    return AssignmentValidator(GitAuthConfigurer(tokenManager))
  }

  fun check(
    assignment: ProjectAssignment,
    current: String = instanceUrl,
    remotes: List<String> = listOf(assignment.remoteUrl),
  ) = validator().check(assignment, current, remotes)

  describe("check") {
    it("accepts an assignment whose instance, remote and host all line up") {
      check(assignment()) shouldBe AssignmentCheck.Accepted
    }

    it("accepts an ssh remote on the instance host") {
      check(assignment("git@gitlab.com:g/p.git")) shouldBe AssignmentCheck.Accepted
    }

    it("accepts when the stored remote and the configured one differ only by .git") {
      val stored = assignment("https://gitlab.com/g/p.git")

      check(stored, remotes = listOf("https://gitlab.com/g/p")) shouldBe AssignmentCheck.Accepted
    }

    it("rejects an assignment made against another instance (A14)") {
      check(assignment(instance = "https://other.example"), current = instanceUrl) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.INSTANCE_CHANGED)
    }

    it("rejects when the assigned remote is no longer configured") {
      check(assignment(), remotes = listOf("https://gitlab.com/other/repo.git")) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_GONE)
    }

    it("rejects when the repository has no remotes at all") {
      check(assignment(), remotes = emptyList()) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_GONE)
    }

    it("rejects a remote on GitHub even though the instance and the remote both check out") {
      // Conditions 1 and 2 pass here: the instance matches, and the remote is configured. Without
      // condition 3 this repository would resolve to a GitLab project it has nothing to do with.
      check(assignment("https://github.com/g/p.git")) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_NOT_ON_INSTANCE)
    }

    it("rejects a remote on a different GitLab instance") {
      check(assignment("https://gitlab.other.example/g/p.git")) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_NOT_ON_INSTANCE)
    }

    it("rejects an ssh remote on a different host") {
      check(assignment("git@elsewhere.example:g/p.git")) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_NOT_ON_INSTANCE)
    }

    it("accepts an ssh remote with an explicit port, which no setting can contradict") {
      // U7: no GitLab SSH port setting exists, so a port on one side only is not a mismatch.
      check(assignment("ssh://git@gitlab.com:2222/g/p.git")) shouldBe AssignmentCheck.Accepted
    }

    it("rejects an http remote on the instance host but the wrong port") {
      val stored = assignment("https://gitlab.com:8443/g/p.git")

      check(stored) shouldBe AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_NOT_ON_INSTANCE)
    }

    it("checks the instance before anything else, so a stale assignment reports that first") {
      val stale = assignment("https://github.com/g/p.git", instance = "https://other.example")

      check(stale) shouldBe AssignmentCheck.Rejected(AssignmentCheck.Reason.INSTANCE_CHANGED)
    }

    it("does not match two projects that differ only in path case") {
      check(assignment("https://gitlab.com/g/Repo.git"), remotes = listOf("https://gitlab.com/g/repo.git")) shouldBe
        AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_GONE)
    }
  }
})
