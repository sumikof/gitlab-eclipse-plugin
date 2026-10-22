package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved
import com.gitlab.eclipse.views.sidebar.autorefresh.SettleOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.eclipse.ui.IWorkbenchWindow

/** A listener that records which state it was created for and the callbacks it gets. */
private class RecordingListener(val state: WindowCurrentBranchState) : CurrentBranchListener {
  var started = 0

  override fun onRefreshStarted(id: Long, soft: Boolean) {
    started++
  }

  override fun onRefreshFinished(id: Long, outcome: SettleOutcome?) = Unit
}

private fun shownPipeline(status: String) = Resolved(
  null,
  Result.success(
    PipelineShown(
      PipelineSnapshot(
        GitLabPipeline(id = 7, projectId = 42, status = status, webUrl = "https://gl.example.com/p/-/pipelines/7"),
        PipelineKind.BRANCH,
        Result.success(emptyList()),
        Result.success(emptyList()),
        "https://gl.example.com",
        "fp",
      ),
    ),
  ),
)

/**
 * T6d (V6-16/17/18): how the module wires the CI-03 notifier — one notifier per window state, one dedupe for the whole
 * process, and a popup gate that closes once the window's state is disposed.
 */
class CurrentBranchModuleWiringTest : StringSpec({
  "each created window state gets exactly one notifier, subscribed to it" {
    val listeners = mutableListOf<RecordingListener>()
    val create = withPipelineNotifier(
      createBase = { StateRig().state },
      notifierFor = { state, _ -> RecordingListener(state).also { listeners += it } },
    )

    val a = create(mockk<IWorkbenchWindow>())
    val b = create(mockk<IWorkbenchWindow>())
    listOf(a, b).forEach { it.start() } // each starts its first refresh: its listeners hear it

    listeners.map { it.state } shouldContainExactly listOf(a, b)
    listeners.map { it.started } shouldContainExactly listOf(1, 1)
  }

  "one dedupe is shared by every window's notifier" {
    val dedupes = mutableListOf<PipelineNotificationDedupe>()
    val create = withPipelineNotifier(
      createBase = { StateRig().state },
      notifierFor = { state, dedupe -> RecordingListener(state).also { dedupes += dedupe } },
    )

    create(mockk<IWorkbenchWindow>())
    create(mockk<IWorkbenchWindow>())

    dedupes shouldHaveSize 2
    dedupes[1] shouldBeSameInstanceAs dedupes[0]
  }

  "the production notifier's popup gate is closed once the window's state is disposed" {
    val rig = StateRig()
    val gates = mutableListOf<() -> Boolean>()
    val notifier = pipelineStatusNotifier(
      state = rig.state,
      dedupe = PipelineNotificationDedupe(),
      isEnabled = { true },
      show = { _, _, _, gate -> gates += gate },
      openUrl = {},
    )
    val snapshot = { status: String -> CurrentBranchSnapshot(1, "/repo/.git", "/repo", shownPipeline(status), false) }
    notifier.onApplied(1, snapshot("running"))
    notifier.onApplied(2, snapshot("failed"))

    gates.single()() shouldBe true
    rig.state.dispose()
    gates.single()() shouldBe false
  }

  "the production notifier reads the preference at notify time" {
    var enabled = false
    val shown = mutableListOf<String>()
    val notifier = pipelineStatusNotifier(
      state = StateRig().state,
      dedupe = PipelineNotificationDedupe(),
      isEnabled = { enabled },
      show = { _, message, _, _ -> shown += message },
      openUrl = {},
    )
    val snapshot = { status: String -> CurrentBranchSnapshot(1, "/repo/.git", "/repo", shownPipeline(status), false) }
    notifier.onApplied(1, snapshot("running"))
    notifier.onApplied(2, snapshot("failed"))
    enabled = true
    notifier.onApplied(3, snapshot("success"))

    shown shouldContainExactly listOf("Pipeline Passed")
  }
})
