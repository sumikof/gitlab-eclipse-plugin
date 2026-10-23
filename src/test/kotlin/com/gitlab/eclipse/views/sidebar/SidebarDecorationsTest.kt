package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.CiStatusIcon
import com.gitlab.eclipse.ci.JobEntry
import com.gitlab.eclipse.ci.aggregateStatusName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp0"

private fun job(status: String?, allowFailure: Boolean? = null) =
  JobNode(GitLabJob(id = 1, name = "j", status = status, allowFailure = allowFailure), 1L, SRC_URL, SRC_FP)

private fun bridge(status: String?, allowFailure: Boolean? = null) =
  BridgeNode(GitLabBridge(id = 2, name = "b", status = status, allowFailure = allowFailure), 1L, SRC_URL, SRC_FP)

private fun stage(vararg children: SidebarNode, statusName: String = "x") =
  StageNode("build", statusName, children.toList())

private fun downstream(status: String?, id: Long = 5) =
  DownstreamPipelineNode("br", GitLabPipeline(id = id, status = status), 1, emptySet(), SRC_URL, SRC_FP)

private fun file(
  type: ChangeType,
  oldPath: String? = "dir/sub/file.kt",
  newPath: String? = "dir/sub/file.kt",
  inTree: Boolean = false,
) = ChangedFileNode(oldPath, newPath, type, "sha", inTree = inTree)

class SidebarDecorationsTest : StringSpec({
  "pipeline icon follows its status and ignores allowFailure" {
    val p = PipelineNode(GitLabPipeline(id = 1, status = "failed"), emptyList(), false, false, SRC_URL, SRC_FP)
    p.status shouldBe "failed"
    sidebarStatusIcon(p) shouldBe CiStatusIcon.FAILED
  }
  "job and bridge icons honour allowFailure, null counts as false" {
    sidebarStatusIcon(job("failed", true)) shouldBe CiStatusIcon.FAILED_ALLOWED
    sidebarStatusIcon(job("failed", null)) shouldBe CiStatusIcon.FAILED
    sidebarStatusIcon(bridge("failed", true)) shouldBe CiStatusIcon.FAILED_ALLOWED
    sidebarStatusIcon(bridge("failed", null)) shouldBe CiStatusIcon.FAILED
  }
  "stage icon is the aggregate of its jobs and bridges" {
    sidebarStatusIcon(stage(job("success"), job("failed", true))) shouldBe CiStatusIcon.FAILED_ALLOWED
    sidebarStatusIcon(stage(job("success"), job("running"))) shouldBe CiStatusIcon.RUNNING
    sidebarStatusIcon(stage(job("success"), bridge("failed"))) shouldBe CiStatusIcon.FAILED
    sidebarStatusIcon(stage(job("success"), bridge("failed", true))) shouldBe CiStatusIcon.FAILED_ALLOWED
  }
  "empty stage is unknown" {
    sidebarStatusIcon(stage()) shouldBe CiStatusIcon.UNKNOWN
  }
  "stage icon and statusName follow the same tie rule" {
    val manual = JobEntry(GitLabJob(id = 1, status = "manual"), null)
    val unknown = JobEntry(GitLabJob(id = 2, status = null), null)
    val manualFirst = stage(job("manual"), job(null), statusName = aggregateStatusName(listOf(manual, unknown)))
    val unknownFirst = stage(job(null), job("manual"), statusName = aggregateStatusName(listOf(unknown, manual)))
    sidebarStatusIcon(manualFirst) shouldBe CiStatusIcon.MANUAL
    manualFirst.statusName shouldBe "Manual"
    manualFirst.label shouldBe "build · Manual"
    sidebarStatusIcon(unknownFirst) shouldBe CiStatusIcon.UNKNOWN
    unknownFirst.statusName shouldBe "Status Unknown"
  }
  "downstream group icon is the aggregate of its pipelines" {
    sidebarStatusIcon(DownstreamPipelinesNode(1, emptyList())) shouldBe CiStatusIcon.UNKNOWN
    val g = DownstreamPipelinesNode(1, listOf(downstream("success", 5), downstream("failed", 6)))
    sidebarStatusIcon(g) shouldBe CiStatusIcon.FAILED
    g.label shouldBe "Downstream pipelines · Failed"
    DownstreamPipelinesNode(1, emptyList()).label shouldBe "Downstream pipelines · Status Unknown"
  }
  "downstream pipeline icon follows its status" {
    sidebarStatusIcon(downstream("running")) shouldBe CiStatusIcon.RUNNING
  }
  "nodes without CI status have no icon" {
    val issue = GitLabIssue(1, 1, "t", "https://h/i", "opened", null)
    val mr = GitLabMergeRequest(id = 1, iid = 1, title = "t", projectId = 1, webUrl = "https://h/m", state = "opened")
    sidebarStatusIcon(IssueNode(issue)) shouldBe null
    sidebarStatusIcon(MergeRequestNode(mr)) shouldBe null
    sidebarStatusIcon(file(ChangeType.NEW)) shouldBe null
    sidebarStatusIcon(MessageNode("m")) shouldBe null
  }

  "change badges for each change type" {
    changeBadge(file(ChangeType.NEW)) shouldBe ChangeBadge('A', ChangeColorRole.ADDED)
    changeBadge(file(ChangeType.DELETED)) shouldBe ChangeBadge('D', ChangeColorRole.DELETED)
    changeBadge(file(ChangeType.RENAMED)) shouldBe ChangeBadge('R', ChangeColorRole.RENAMED)
    changeBadge(file(ChangeType.MODIFIED)) shouldBe ChangeBadge('M', null)
    changeBadge(MessageNode("m")) shouldBe null
  }
  "display text: list shows the full path, tree only the last segment" {
    sidebarDisplayText(file(ChangeType.MODIFIED)) shouldBe "dir/sub/file.kt · M"
    sidebarDisplayText(file(ChangeType.MODIFIED, inTree = true)) shouldBe "file.kt · M"
    file(ChangeType.MODIFIED, inTree = true).label shouldBe "dir/sub/file.kt"
  }
  "display text for each type, deleted file and slash-less path" {
    sidebarDisplayText(file(ChangeType.NEW)) shouldBe "dir/sub/file.kt · A"
    sidebarDisplayText(file(ChangeType.RENAMED)) shouldBe "dir/sub/file.kt · R"
    sidebarDisplayText(file(ChangeType.DELETED, oldPath = "old/x.kt", newPath = null, inTree = true)) shouldBe "x.kt · D"
    sidebarDisplayText(file(ChangeType.MODIFIED, "README.md", "README.md", inTree = true)) shouldBe "README.md · M"
  }
  "display text of other nodes is the label" {
    val m = MessageNode("hello")
    sidebarDisplayText(m) shouldBe "hello"
    val s = stage(job("success"))
    sidebarDisplayText(s) shouldBe s.label
  }

  "change color ids" {
    val p = "com.gitlab.eclipse.sidebar."
    changeColorId(ChangeColorRole.ADDED, false) shouldBe p + "changeAddedForeground"
    changeColorId(ChangeColorRole.DELETED, false) shouldBe p + "changeDeletedForeground"
    changeColorId(ChangeColorRole.RENAMED, false) shouldBe p + "changeRenamedForeground"
    changeColorId(ChangeColorRole.ADDED, true) shouldBe p + "changeAddedForegroundDark"
    changeColorId(ChangeColorRole.DELETED, true) shouldBe p + "changeDeletedForegroundDark"
    changeColorId(ChangeColorRole.RENAMED, true) shouldBe p + "changeRenamedForegroundDark"
  }
})
