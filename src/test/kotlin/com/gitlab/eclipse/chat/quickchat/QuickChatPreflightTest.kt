package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.navigation.ProjectResolution
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

class QuickChatPreflightTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val api = mockk<QuickChatApi>()
  val file = File("/work/proj/src/A.kt")
  val clock = FakeClock()
  fun budget(left: kotlin.time.Duration = 120.seconds) =
    RequestBudget(clock, clock.nanoTime() + left.inWholeNanoseconds, 25.seconds)

  fun project(namespaceWithPath: String = "group/proj", instanceUrl: String = INSTANCE) = GitLabProjectInfo(
    gitDir = File("/work/proj/.git"),
    workTree = File("/work/proj"),
    namespaceWithPath = namespaceWithPath,
    instanceUrl = instanceUrl,
    webUrl = "$instanceUrl/$namespaceWithPath",
    remoteName = "origin",
  )

  fun preflight(resolution: ProjectResolution) = QuickChatPreflight(api) { resolution }

  beforeEach {
    clearMocks(api)
    every { api.version(any(), any()) } returns "17.10.0"
    every { api.project(any(), any(), any()) } returns ProjectInfo("gid://gitlab/Project/1", true)
  }

  describe("project resolution kinds (A22)") {
    it("sends a file outside any repository without a project, asking only the version") {
      val result = preflight(ProjectResolution.NotInRepository).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(Preflight(null, ProjectKey.NOT_IN_REPOSITORY), false)
      verify(exactly = 1) { api.version(any(), any()) }
      verify(exactly = 0) { api.project(any(), any(), any()) }
    }
    it("sends a file without a GitLab remote without a project, asking only the version") {
      val result = preflight(ProjectResolution.NoGitLabRemote).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(Preflight(null, ProjectKey.NO_GITLAB_REMOTE), false)
      verify(exactly = 0) { api.project(any(), any(), any()) }
    }
    it("refuses a failed resolution without any request") {
      val result = preflight(ProjectResolution.Failed).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.RESOLUTION_FAILED))
      verify(exactly = 0) { api.version(any(), any()) }
    }
    it("refuses when the resolver throws (fail closed)") {
      val throwing = QuickChatPreflight(api) { throw IllegalStateException("boom") }
      val result = throwing.check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.RESOLUTION_FAILED))
    }
    it("refuses a project on another instance without any request") {
      val result = preflight(ProjectResolution.Resolved(project(instanceUrl = "https://gitlab.example.com")))
        .check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.OTHER_INSTANCE))
      verify(exactly = 0) { api.version(any(), any()) }
    }
    it("compares instances normalized (trailing slash)") {
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot("$INSTANCE/"), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(PROJECT_PREFLIGHT, false)
    }
    it("passes the resolver the anchor file as given (null included)") {
      val seen = mutableListOf<File?>()
      val recording = QuickChatPreflight(api) {
        seen += it
        ProjectResolution.NotInRepository
      }
      recording.check(snapshot(), null, null, budget())
      seen shouldBe listOf(null)
    }
  }

  describe("Q1 results (A22, A8)") {
    it("uses the project id as resourceId when Duo is enabled") {
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(PROJECT_PREFLIGHT, false)
      verify { api.project(any(), "group/proj", any()) }
    }
    it("sends with resourceId when duoFeaturesEnabled is null (server decides)") {
      every { api.project(any(), any(), any()) } returns ProjectInfo("gid://gitlab/Project/1", null)
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(PROJECT_PREFLIGHT, false)
    }
    it("refuses with Unavailable when Duo is turned off for the project") {
      every { api.project(any(), any(), any()) } returns ProjectInfo("gid://gitlab/Project/1", false)
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(
        QuickChatOutcome.Unavailable(QuickChatOutcome.Unavailable.DUO_DISABLED_FOR_PROJECT),
      )
    }
    it("refuses when the project is not found") {
      every { api.project(any(), any(), any()) } returns null
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.PROJECT_NOT_FOUND))
    }
    it("stops with Unsupported below 17.10 and never asks for the project (pre-16.9 schema safe)") {
      every { api.version(any(), any()) } returns "16.8.2-ee"
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.Unsupported("16.8.2-ee"))
      verify(exactly = 0) { api.project(any(), any(), any()) }
    }
    it("continues when the version is null or unparsable") {
      listOf(null, "unknown").forEach { v ->
        every { api.version(any(), any()) } returns v
        val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, budget())
        result shouldBe QuickChatPreflight.Result.Proceed(PROJECT_PREFLIGHT, false)
      }
    }
    it("lets a Q1 failure propagate for the service to classify") {
      every { api.version(any(), any()) } throws GraphQlException(true, listOf("x"))
      shouldThrow<GraphQlException> {
        preflight(ProjectResolution.NotInRepository).check(snapshot(), file, null, budget())
      }
    }
  }

  describe("fullPath decoding (A25)") {
    it("queries the decoded path of a percent-escaped HTTP remote") {
      val resolved = ProjectResolution.Resolved(project("gr%C3%BCp/pr%20oj"))
      val result = preflight(resolved).check(snapshot(), file, null, budget())
      verify { api.project(any(), "grüp/pr oj", any()) }
      result shouldBe QuickChatPreflight.Result.Proceed(
        Preflight("gid://gitlab/Project/1", ProjectKey.resolved(INSTANCE, "grüp/pr oj")),
        false,
      )
    }
    it("fails closed on a malformed escape without any request") {
      val result = preflight(ProjectResolution.Resolved(project("gr%ZZp/proj"))).check(snapshot(), file, null, budget())
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.RESOLUTION_FAILED))
      verify(exactly = 0) { api.version(any(), any()) }
    }
  }

  describe("reuse per binding (A26)") {
    it("reuses the bound preflight when the project key is unchanged, with no request") {
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "thread-1")
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, binding, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(PROJECT_PREFLIGHT, false)
      verify(exactly = 0) { api.version(any(), any()) }
      verify(exactly = 0) { api.project(any(), any(), any()) }
    }
    it("redoes the preflight and reports a project change when the key differs") {
      every { api.project(any(), "group/other", any()) } returns ProjectInfo("gid://gitlab/Project/2", true)
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "thread-1")
      val resolved = ProjectResolution.Resolved(project("group/other"))
      val result = preflight(resolved).check(snapshot(), file, binding, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(
        Preflight("gid://gitlab/Project/2", ProjectKey.resolved(INSTANCE, "group/other")),
        true,
      )
    }
    it("treats a project file becoming a loose file as a project change") {
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "thread-1")
      val result = preflight(ProjectResolution.NotInRepository).check(snapshot(), file, binding, budget())
      result shouldBe QuickChatPreflight.Result.Proceed(Preflight(null, ProjectKey.NOT_IN_REPOSITORY), true)
    }
  }

  describe("deadline") {
    it("stops before the version query when no time is left") {
      val result = preflight(ProjectResolution.NotInRepository).check(snapshot(), file, null, budget(0.seconds))
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.TimedOut(beforeSend = true, update = null))
      verify(exactly = 0) { api.version(any(), any()) }
    }
    it("stops before the project query when the version query used up the time") {
      val b = budget(10.seconds)
      every { api.version(any(), any()) } answers {
        clock.advance(10.seconds)
        "17.10.0"
      }
      val result = preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, b)
      result shouldBe QuickChatPreflight.Result.Stop(QuickChatOutcome.TimedOut(beforeSend = true, update = null))
      verify(exactly = 0) { api.project(any(), any(), any()) }
    }
    it("bounds each query by min(request timeout, time left)") {
      val b = budget(7.seconds)
      every { api.version(any(), any()) } answers {
        clock.advance(2.seconds)
        "17.10.0"
      }
      preflight(ProjectResolution.Resolved(project())).check(snapshot(), file, null, b)
      verify { api.version(any(), Duration.ofSeconds(7)) }
      verify { api.project(any(), any(), Duration.ofSeconds(5)) }

      val roomy = budget(100.seconds)
      preflight(ProjectResolution.NotInRepository).check(snapshot(), file, null, roomy)
      verify { api.version(any(), Duration.ofSeconds(25)) }
    }
  }
})
