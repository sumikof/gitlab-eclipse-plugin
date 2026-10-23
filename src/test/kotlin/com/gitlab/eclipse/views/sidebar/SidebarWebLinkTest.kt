package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private const val URL = "https://gitlab.example.com/g/p/-/x/1"
private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp0"

private val RAW_CASES: List<Pair<String?, String?>> = listOf(
  null to null,
  "" to null,
  "   " to null,
  "https://gitlab.com/g/p/-/issues/1" to "https://gitlab.com/g/p/-/issues/1",
  "http://host/x" to "http://host/x",
  "HTTPS://gitlab.com/x" to "HTTPS://gitlab.com/x",
  "file:///etc/passwd" to null,
  "file://attacker/share/x.exe" to null,
  "javascript:alert(1)" to null,
  "mailto:a@b" to null,
  "ftp://h/x" to null,
  "http:foo" to null,
  "https://exa mple.com" to null,
  " https://h/x " to "https://h/x",
)

private fun pipelineNode(url: String?) = PipelineNode(
  GitLabPipeline(id = 1, status = "success", webUrl = url),
  emptyList(), canRetry = false, canCancel = false, sourceInstanceUrl = SRC_URL, sourceAuthFingerprint = SRC_FP,
)

private fun downstream(url: String?) = DownstreamPipelineNode(
  "bridge", GitLabPipeline(id = 2, status = "success", webUrl = url), 1, emptySet(), SRC_URL, SRC_FP,
)

class SidebarWebLinkTest : DescribeSpec({
  describe("openableWebUrl") {
    withData(nameFn = { "'${it.first}'" }, RAW_CASES) { (raw, expected) ->
      openableWebUrl(raw) shouldBe expected
    }
  }

  describe("sidebarWebUrl") {
    it("returns the URL of an issue") {
      val issue = GitLabIssue(1, 1, "t", "https://gitlab.example.com/i/1", "opened", null)
      sidebarWebUrl(IssueNode(issue)) shouldBe "https://gitlab.example.com/i/1"
    }
    it("returns null, without throwing, for an issue whose web_url is null") {
      // Gson can put a null into the non-null Kotlin field, as a real `"web_url": null` response would.
      val json = """{"id":1,"iid":1,"title":"t","web_url":null,"state":"opened"}"""
      val node = IssueNode(Gson().fromJson(json, GitLabIssue::class.java))
      sidebarWebUrl(node) shouldBe null
    }
    it("returns the url of a merge request although it is not activatable") {
      val mr = GitLabMergeRequest(id = 1, iid = 3, title = "t", projectId = 9, webUrl = URL, state = "opened")
      val node = MergeRequestNode(mr)
      node.activationUrl shouldBe null
      sidebarWebUrl(node) shouldBe URL
    }
    it("returns the URL of overview, pipeline, job, bridge and downstream pipeline nodes") {
      sidebarWebUrl(OverviewNode(URL)) shouldBe URL
      sidebarWebUrl(pipelineNode(URL)) shouldBe URL
      sidebarWebUrl(JobNode(GitLabJob(id = 1, webUrl = URL), 1L, SRC_URL, SRC_FP)) shouldBe URL
      sidebarWebUrl(BridgeNode(GitLabBridge(id = 1, webUrl = URL), 1L, SRC_URL, SRC_FP)) shouldBe URL
      sidebarWebUrl(downstream(URL)) shouldBe URL
    }
    it("returns null when the node's URL is missing or not http(s)") {
      sidebarWebUrl(pipelineNode(null)) shouldBe null
      sidebarWebUrl(pipelineNode("file:///x")) shouldBe null
      sidebarWebUrl(OverviewNode("javascript:alert(1)")) shouldBe null
      sidebarWebUrl(downstream(null)) shouldBe null
    }
    it("returns null for nodes that have no web page") {
      val perms = GitLabNotePermissions(resolveNote = false, adminNote = false, createNote = false)
      val nodes: List<SidebarNode> = listOf(
        ChangedFileNode(null, "a.kt", ChangeType.NEW, null),
        StageNode("build", "Passed", emptyList()),
        DownstreamPipelinesNode(1, emptyList()),
        MessageNode("m"),
        QueryRootNode("q", "Q", emptyList()),
        CurrentBranchSectionNode(emptyList()),
        ProjectGroupNode("g", emptyList()),
        ChangedDirectoryNode("d", emptyList()),
        DiscussionsSectionNode(SRC_URL, SRC_FP, 1, 1, "gid", null, "g/p"),
        ThreadNode(SRC_URL, SRC_FP, 1, 1, "gid", null, "g/p", "r", false, true, null, perms, emptyList()),
        NoteNode(SRC_URL, SRC_FP, 1, 1, "gid", null, "g/p", "n", "r", "body", "u", "2026-01-01", perms),
      )
      nodes.map { sidebarWebUrl(it) } shouldBe nodes.map { null }
    }
  }
})
