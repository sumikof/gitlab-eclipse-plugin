package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val INSTANCE = "https://gl.example.com"
private const val WEB_URL = "$INSTANCE/p/-/pipelines/7"

private fun pipeline(
  id: Long = 7,
  status: String = "running",
  ref: String? = "main",
  webUrl: String? = WEB_URL,
  instance: String = INSTANCE,
) = Resolved(
  null,
  Result.success(
    PipelineShown(
      PipelineSnapshot(
        GitLabPipeline(id = id, projectId = 42, status = status, ref = ref, webUrl = webUrl),
        PipelineKind.BRANCH,
        Result.success(emptyList()),
        Result.success(emptyList()),
        instance,
        "fp",
      ),
    ),
  ),
)

private fun snap(input: CurrentBranchSectionInput?, gitDir: String?) =
  CurrentBranchSnapshot(1, gitDir, "/repo", input, false)

/** One shown popup: its message, links and gate. */
private class Shown(val message: String, val links: List<Pair<String, () -> Unit>>, val gate: () -> Boolean)

private class NotifierRig(
  var enabled: Boolean = true,
  val dedupe: PipelineNotificationDedupe = PipelineNotificationDedupe(),
) {
  val shown = mutableListOf<Shown>()
  val opened = mutableListOf<String>()
  var windowAlive = true
  val notifier = PipelineStatusNotifier(
    isEnabled = { enabled },
    isWindowAlive = { windowAlive },
    dedupe = dedupe,
    show = { _, message, links, gate -> shown += Shown(message, links, gate) },
    openUrl = { opened += it },
  )

  fun apply(input: CurrentBranchSectionInput?, gitDir: String? = "/repo/.git") =
    notifier.onApplied(1, snap(input, gitDir))
}

class PipelineStatusNotifierTest : StringSpec({
  "CI-03: the first observation is silent; a status change of the same pipeline notifies with View in GitLab" {
    val rig = NotifierRig()

    rig.apply(pipeline(status = "running"))
    rig.shown.shouldBeEmpty()

    rig.apply(pipeline(status = "success"))
    rig.shown shouldHaveSize 1
    rig.shown.single().message shouldBe "Pipeline Passed (main)"
    rig.shown.single().links.map { it.first } shouldContainExactly listOf("View in GitLab")
    rig.shown.single().links.single().second()
    rig.opened shouldContainExactly listOf(WEB_URL)
  }

  "D3: a new pipeline id is silent at first, its own transitions notify" {
    val rig = NotifierRig()
    rig.apply(pipeline(id = 7, status = "failed"))

    rig.apply(pipeline(id = 8, status = "running"))
    rig.shown.shouldBeEmpty()
    rig.apply(pipeline(id = 8, status = "failed"))
    rig.shown shouldHaveSize 1
  }

  "the same status again (an MR-side apply, an unchanged refresh) does not notify" {
    val rig = NotifierRig()
    rig.apply(pipeline(status = "running"))
    rig.apply(pipeline(status = "running"))

    rig.shown.shouldBeEmpty()
  }

  "failures, no pipeline, no repository and placeholders neither notify nor reset the memory" {
    val rig = NotifierRig()
    rig.apply(pipeline(status = "running"))

    rig.apply(Resolved(null, Result.failure(IllegalStateException("down"))))
    rig.apply(Resolved(null, Result.success(NoPipeline)))
    rig.apply(Resolved(null, null))
    rig.apply(NoRepository, gitDir = null)
    rig.shown.shouldBeEmpty()

    rig.apply(pipeline(status = "failed"))
    rig.shown shouldHaveSize 1
  }

  "default off: nothing is shown while the preference is off, and turning it on replays nothing" {
    val rig = NotifierRig(enabled = false)
    rig.apply(pipeline(status = "running"))
    rig.apply(pipeline(status = "failed"))
    rig.shown.shouldBeEmpty()

    rig.enabled = true
    rig.apply(pipeline(status = "failed"))
    rig.shown.shouldBeEmpty()
    rig.apply(pipeline(status = "success"))
    rig.shown shouldHaveSize 1
  }

  "the popup's gate re-checks the preference and the window at show time" {
    val rig = NotifierRig()
    rig.apply(pipeline(status = "running"))
    rig.apply(pipeline(status = "failed"))
    val gate = rig.shown.single().gate

    gate() shouldBe true
    rig.enabled = false
    gate() shouldBe false
    rig.enabled = true
    rig.windowAlive = false
    gate() shouldBe false
  }

  "two windows on the same repository notify once per transition (process-wide dedupe)" {
    val dedupe = PipelineNotificationDedupe()
    val a = NotifierRig(dedupe = dedupe)
    val b = NotifierRig(dedupe = dedupe)
    listOf(a, b).forEach { it.apply(pipeline(status = "running")) }

    listOf(a, b).forEach { it.apply(pipeline(status = "failed")) }

    (a.shown.size + b.shown.size) shouldBe 1
  }

  "a pipeline without a web URL notifies without a link; a missing ref drops the suffix" {
    val rig = NotifierRig()
    rig.apply(pipeline(status = "running", ref = null, webUrl = null))
    rig.apply(pipeline(status = "canceled", ref = null, webUrl = null))

    rig.shown.single().message shouldBe "Pipeline Cancelled"
    rig.shown.single().links.shouldBeEmpty()
  }

  "the status is observed while notifications are off: turning them on, the next change notifies" {
    val rig = NotifierRig(enabled = false)
    rig.apply(pipeline(status = "running"))

    rig.enabled = true
    rig.apply(pipeline(status = "success"))

    rig.shown shouldHaveSize 1
  }

  "every transition of one pipeline notifies, in order (running, failed, passed)" {
    val rig = NotifierRig()

    rig.apply(pipeline(status = "running"))
    rig.apply(pipeline(status = "failed"))
    rig.apply(pipeline(status = "success"))

    rig.shown.map { it.message } shouldContainExactly listOf("Pipeline Failed (main)", "Pipeline Passed (main)")
  }

  "the process-wide dedupe keys on the GitLab instance: another instance notifies too" {
    val dedupe = PipelineNotificationDedupe()
    val a = NotifierRig(dedupe = dedupe)
    val b = NotifierRig(dedupe = dedupe)
    a.apply(pipeline(status = "running"))
    b.apply(pipeline(status = "running", instance = "https://other.example.com"))

    a.apply(pipeline(status = "failed"))
    b.apply(pipeline(status = "failed", instance = "https://other.example.com"))

    a.shown shouldHaveSize 1
    b.shown shouldHaveSize 1
  }
})
