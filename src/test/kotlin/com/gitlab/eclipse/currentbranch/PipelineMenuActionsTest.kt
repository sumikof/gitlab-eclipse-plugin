package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.ci.actions.PipelineWrite
import com.gitlab.eclipse.ci.actions.PipelineWriteAction
import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private const val INSTANCE = "https://gl.example.com"

private fun snapshotOf(input: CurrentBranchSectionInput?, headKind: HeadKind = HeadKind.BRANCH) =
  CurrentBranchSnapshot(1, "/g/.git", "/g", input, refreshing = false, headKind = headKind)

private fun shown(
  status: String = "running",
  projectId: Long? = 42,
  webUrl: String? = "$INSTANCE/p/-/pipelines/7",
  jobs: List<GitLabJob> = emptyList(),
) = Resolved(
  null,
  Result.success(
    PipelineShown(
      PipelineSnapshot(
        GitLabPipeline(id = 7, projectId = projectId, status = status, webUrl = webUrl),
        PipelineKind.BRANCH,
        Result.success(jobs),
        Result.success(emptyList()),
        INSTANCE,
        "fp-1",
      ),
    ),
  ),
)

/** Every port recorded; [opens] answers whether the browser launched. */
private class MenuRig(var opens: Boolean = true) {
  val calls = mutableListOf<String>()
  val writes = mutableListOf<PipelineWrite>()
  val successCallbacks = mutableListOf<() -> Unit>()
  val actions = PipelineMenuActions(
    openUrl = { url ->
      calls += "open $url"
      opens
    },
    writePipeline = { write, onSuccess ->
      writes += write
      successCallbacks += onSuccess
    },
    createPipeline = { calls += "create" },
    refresh = { calls += "refresh" },
    requestRefresh = { calls += "requestRefresh" },
    notify = { calls += "notify $it" },
  )

  fun item(snapshot: CurrentBranchSnapshot, label: String) = actions.itemsFor(snapshot).single { it.label == label }
}

class PipelineMenuActionsTest : StringSpec({
  "the items follow PipelineMenuModel: labels, order and enablement" {
    val rig = MenuRig()

    val items = rig.actions.itemsFor(snapshotOf(shown(status = "running")))

    items.map { it.label to it.enabled } shouldContainExactly listOf(
      "View pipeline in GitLab" to true,
      "Retry pipeline" to false,
      "Cancel pipeline" to true,
      "Create new pipeline…" to true,
      "Refresh" to true,
    )
  }

  "View opens the snapshot pipeline's web URL (no re-fetch); a failed launch notifies without the URL" {
    val rig = MenuRig(opens = false)

    rig.item(snapshotOf(shown()), "View pipeline in GitLab").run()

    rig.calls shouldContainExactly listOf(
      "open $INSTANCE/p/-/pipelines/7",
      "notify Couldn't open the pipeline in your browser.",
    )
  }

  "R12/R13: Retry and Cancel write pinned to the snapshot's connection tags; success requests a window refresh" {
    val rig = MenuRig()

    rig.item(snapshotOf(shown(status = "failed")), "Retry pipeline").run()
    rig.item(snapshotOf(shown(status = "running")), "Cancel pipeline").run()

    rig.writes shouldContainExactly listOf(
      PipelineWrite(PipelineWriteAction.RETRY, 42, 7, INSTANCE, "fp-1"),
      PipelineWrite(PipelineWriteAction.CANCEL, 42, 7, INSTANCE, "fp-1"),
    )
    rig.calls.shouldBeEmpty()
    rig.successCallbacks.forEach { it() }
    rig.calls shouldContainExactly listOf("requestRefresh", "requestRefresh")
  }

  "a disabled entry does nothing when run (no write without a project id)" {
    val rig = MenuRig()

    rig.item(snapshotOf(shown(status = "failed", projectId = null)), "Retry pipeline").run()

    rig.writes.shouldBeEmpty()
  }

  "Create runs the existing Create-pipeline command; Refresh is the window's full refresh" {
    val rig = MenuRig()

    rig.item(snapshotOf(Resolved(null, Result.success(NoPipeline))), "Create new pipeline…").run()
    rig.item(snapshotOf(Resolved(null, Result.success(NoPipeline))), "Refresh").run()

    rig.calls shouldContainExactly listOf("create", "refresh")
  }

  "Download artifacts: one child per finished job, each opening that job's archive URL" {
    val jobs = listOf(
      GitLabJob(id = 1, name = "build", status = "success", webUrl = "$INSTANCE/p/-/jobs/1"),
      GitLabJob(id = 2, name = "test", status = "running", webUrl = "$INSTANCE/p/-/jobs/2"),
      GitLabJob(id = 3, name = null, status = "failed", webUrl = null),
    )
    val rig = MenuRig()

    val artifacts = rig.item(snapshotOf(shown(status = "failed", jobs = jobs)), "Download artifacts")
    artifacts.children.map { it.label } shouldContainExactly listOf("build", "Job #3")
    artifacts.children[0].run()
    artifacts.children[1].run()

    rig.calls shouldContainExactly listOf(
      "open $INSTANCE/p/-/jobs/1/artifacts/download?file_type=archive",
      "notify This job has no downloadable artifacts, or its URL is unavailable.",
    )
  }

  "no pipeline: only Create and Refresh; nothing settled at all: only Refresh" {
    val rig = MenuRig()

    rig.actions.itemsFor(snapshotOf(Resolved(null, Result.success(NoPipeline)))).map { it.label } shouldContainExactly
      listOf("Create new pipeline…", "Refresh")
    rig.actions.itemsFor(snapshotOf(null)).map { it.label } shouldContainExactly listOf("Refresh")
  }

  "R12: View is disabled without a web URL" {
    val rig = MenuRig()

    rig.item(snapshotOf(shown(webUrl = null)), "View pipeline in GitLab").enabled shouldBe false
  }
})
