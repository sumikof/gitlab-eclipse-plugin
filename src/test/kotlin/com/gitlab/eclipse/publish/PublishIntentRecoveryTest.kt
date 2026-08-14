package com.gitlab.eclipse.publish

import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.api.ExistingProject
import com.gitlab.eclipse.api.ProjectCreationService
import com.gitlab.eclipse.api.model.GitLabUser
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant

class PublishIntentRecoveryTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val recordedAt = Instant.parse("2026-08-14T10:00:00Z")
  val intent = PublishRecord.Intent(
    repositoryRootPath = "/repo",
    instanceUrl = "https://gitlab.com",
    namespacePath = "g",
    projectPath = "p",
    recordedAt = recordedAt,
  )

  fun found(
    creatorId: Long? = 5,
    createdAt: Instant? = recordedAt.plusSeconds(3),
  ) = ExistingProject(
    id = 9,
    creatorId = creatorId,
    createdAt = createdAt,
    webUrl = "https://gitlab.com/g/p",
    httpUrl = "https://gitlab.com/g/p.git",
  )

  fun projects(project: ExistingProject?): ProjectCreationService = mockk<ProjectCreationService>().also {
    every { it.findProject(any(), any()) } returns project
  }

  fun users(id: Long = 5): CurrentUserService = mockk<CurrentUserService>().also {
    every { it.getCurrentUser() } returns GitLabUser(id, "me")
  }

  fun recovery(
    project: ExistingProject?,
    userId: Long = 5,
    now: Instant = recordedAt.plusSeconds(30),
  ) = PublishIntentRecovery(projects(project), users(userId)) { now }

  describe("decide") {
    it("discards the intent when no project occupies the path") {
      recovery(null).decide(intent, "origin") shouldBe Recovery.Discard
    }

    it("adopts a project the current user created moments after the intent") {
      val decision = recovery(found()).decide(intent, "origin")

      decision.shouldBeInstanceOf<Recovery.Adopt>()
      decision.state.projectId shouldBe 9L
      decision.state.normalizedRemoteUrl shouldBe "https://gitlab.com/g/p"
      decision.state.remoteName shouldBe "origin"
    }

    it("asks rather than adopting when somebody else created the project") {
      recovery(found(creatorId = 77)).decide(intent, "origin").shouldBeInstanceOf<Recovery.Confirm>()
    }

    it("asks rather than adopting when the project predates the intent") {
      val old = found(createdAt = recordedAt.minus(Duration.ofHours(2)))

      recovery(old).decide(intent, "origin").shouldBeInstanceOf<Recovery.Confirm>()
    }

    it("asks rather than adopting when the project was created long after this run") {
      val later = found(createdAt = recordedAt.plus(Duration.ofHours(2)))

      recovery(later).decide(intent, "origin").shouldBeInstanceOf<Recovery.Confirm>()
    }

    it("tolerates clock skew either side of the record") {
      val skewed = found(createdAt = recordedAt.minus(Duration.ofMinutes(3)))

      recovery(skewed).decide(intent, "origin").shouldBeInstanceOf<Recovery.Adopt>()
    }

    it("asks when the server gave no creator or no creation time") {
      recovery(found(creatorId = null)).decide(intent, "origin").shouldBeInstanceOf<Recovery.Confirm>()
      recovery(found(createdAt = null)).decide(intent, "origin").shouldBeInstanceOf<Recovery.Confirm>()
    }

    it("asks when the current user cannot be established") {
      val users = mockk<CurrentUserService>()
      every { users.getCurrentUser() } throws IllegalStateException("no token")

      PublishIntentRecovery(projects(found()), users) { recordedAt }
        .decide(intent, "origin").shouldBeInstanceOf<Recovery.Confirm>()
    }
  }
})
