package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk

private const val SRC = "https://gl"
private const val FP = "fp0"

class SidebarDoubleClickTest : DescribeSpec({
  it("a changed file opens its diff, never the browser") {
    val mrUrl = "https://gl/x/-/merge_requests/1"
    val node = ChangedFileNode("a.kt", "a.kt", ChangeType.MODIFIED, diffHeadSha = null, mrWebUrl = mrUrl)

    val action = sidebarDoubleClick(node)

    action.shouldBeInstanceOf<SidebarDoubleClick.OpenDiff>().node shouldBeSameInstanceAs node
  }

  it("a node with an activation URL opens it in the browser") {
    val node = mockk<SidebarNode> { every { activationUrl } returns "https://gl/x" }

    sidebarDoubleClick(node).shouldBeInstanceOf<SidebarDoubleClick.OpenUrl>().url shouldBe "https://gl/x"
  }

  it("a job opens its log, even when it has a web URL (L6)") {
    val job = GitLabJob(id = 7, name = "compile", status = "failed", webUrl = "https://gl/x/-/jobs/7")
    val node = JobNode(job, 42L, SRC, FP)

    sidebarDoubleClick(node).shouldBeInstanceOf<SidebarDoubleClick.OpenJobLog>().node shouldBeSameInstanceAs node
  }

  it("a job without a web URL still opens its log") {
    val node = JobNode(GitLabJob(id = 8, name = "lint", status = "running"), 42L, SRC, FP)

    sidebarDoubleClick(node).shouldBeInstanceOf<SidebarDoubleClick.OpenJobLog>().node shouldBeSameInstanceAs node
  }

  it("a bridge keeps opening its web page") {
    val bridge = GitLabBridge(id = 9, name = "trigger", status = "failed", webUrl = "https://gl/x/-/jobs/9")
    val node = BridgeNode(bridge, 42L, SRC, FP)

    sidebarDoubleClick(node).shouldBeInstanceOf<SidebarDoubleClick.OpenUrl>().url shouldBe "https://gl/x/-/jobs/9"
  }

  it("pipelines and downstream pipelines keep opening their web page") {
    val pipeline = GitLabPipeline(id = 100, status = "failed", projectId = 42L, webUrl = "https://gl/x/-/pipelines/100")
    val downstream =
      GitLabPipeline(id = 205, status = "failed", projectId = 43L, webUrl = "https://gl/y/-/pipelines/205")

    sidebarDoubleClick(PipelineNode(pipeline, emptyList(), canRetry = false, canCancel = false, SRC, FP))
      .shouldBeInstanceOf<SidebarDoubleClick.OpenUrl>().url shouldBe "https://gl/x/-/pipelines/100"
    sidebarDoubleClick(DownstreamPipelineNode("docs", downstream, 1, setOf(100L), SRC, FP))
      .shouldBeInstanceOf<SidebarDoubleClick.OpenUrl>().url shouldBe "https://gl/y/-/pipelines/205"
  }

  it("anything else does nothing") {
    val node = mockk<SidebarNode> { every { activationUrl } returns null }

    sidebarDoubleClick(node) shouldBe SidebarDoubleClick.None
    sidebarDoubleClick(null) shouldBe SidebarDoubleClick.None
  }
})
