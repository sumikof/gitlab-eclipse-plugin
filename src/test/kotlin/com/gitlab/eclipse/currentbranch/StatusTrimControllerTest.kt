package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.Resolved
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

private const val PERIOD = 30_000L

/** The SWT side as recorded calls. */
private class FakeTrimView : StatusTrimView {
  val renders = mutableListOf<Pair<TrimModel, TrimDiff>>()
  val menus = mutableListOf<List<PipelineMenuItem>>()
  override var isDisposed = false

  override fun render(model: TrimModel, diff: TrimDiff) {
    renders += model to diff
  }

  override fun showPipelineMenu(items: List<PipelineMenuItem>) {
    menus += items
  }
}

private class TrimRig(val state: StateRig = StateRig()) {
  val view = FakeTrimView()
  val calls = mutableListOf<String>()
  val controller = StatusTrimController(
    view = view,
    menuItems = { snapshot -> listOf(PipelineMenuItem("for version ${snapshot.version}", true, {})) },
    openUrl = { calls += "open $it" },
    createMergeRequest = { calls += "createMr" },
    log = { calls += "log $it" },
  )

  val last: TrimModel
    get() = view.renders.last().first
}

private fun withMr(): Resolved {
  val mr = GitLabMergeRequest(
    id = 1,
    iid = 12,
    projectId = 42,
    title = "Secret title",
    webUrl = "https://gl.example.com/p/-/merge_requests/12",
    state = "opened",
  )
  return Resolved(Result.success(CurrentBranchInfo(mr, emptyList())), Result.success(NoPipeline))
}

class StatusTrimControllerTest : StringSpec({
  "attach renders the window's state at once: loading while the first fetch runs" {
    val rig = TrimRig()
    rig.state.state.start()

    rig.controller.attach(rig.state.state)

    rig.view.renders shouldHaveSize 1
    rig.last.pipeline?.text shouldBe "Fetching pipeline…"
  }

  "re-renders on applies and on the refresh's start and finish, only when the model changed" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()

    rig.state.settleBranch(withMr())
    rig.last.mr?.text shouldBe "!12"
    val rendered = rig.view.renders.size

    rig.state.time.advanceBy(PERIOD) // a soft refresh starts and finishes with nothing new
    rig.state.fetch.calls.last().finish(BRANCH_HEAD)
    rig.state.ui.drain()

    rig.view.renders shouldHaveSize rendered
  }

  "the finish re-renders when only the head kind arrived with it (no apply after it)" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()
    rig.state.settleBranch(withMr())

    rig.state.time.advanceBy(PERIOD)
    rig.state.fetch.calls.last().finish(TrackedHead(BRANCH_HEAD.gitDir, HeadKey.Detached("abc"), BRANCH_HEAD.workTree))
    rig.state.ui.drain()

    rig.last shouldBe TrimModel.HIDDEN
  }

  "no repository hides every item" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()

    rig.state.applyLast(NoRepository, gitDir = null)
    rig.state.ui.drain()

    rig.last shouldBe TrimModel.HIDDEN
  }

  "without a window state the trim renders nothing visible" {
    val rig = TrimRig()

    rig.controller.attach(null)

    rig.view.renders.single().first shouldBe TrimModel.HIDDEN
  }

  "clicks: the MR opens its URL, Create MR runs the command, the pipeline shows the menu of the last settled snapshot" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()
    rig.state.settleBranch(withMr())
    rig.state.state.refreshFull() // placeholders while the full refresh runs
    rig.state.applyLast(Resolved(null, null))
    rig.state.ui.drain()
    val settledVersion = rig.state.state.settledSnapshot?.version
    (settledVersion == rig.state.state.snapshot.version) shouldBe false

    rig.controller.onClick(TrimSlot.MR)
    rig.controller.onClick(TrimSlot.PIPELINE)

    rig.calls shouldContainExactly listOf("open https://gl.example.com/p/-/merge_requests/12")
    rig.view.menus.single().single().label shouldBe "for version $settledVersion"

    rig.state.settleBranch(Resolved(Result.success(CurrentBranchInfo(null, emptyList())), Result.success(NoPipeline)))
    rig.controller.onClick(TrimSlot.MR)
    rig.calls.last() shouldBe "createMr"
  }

  "a click on a hidden or inert item does nothing" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()
    rig.state.settleBranch(withMr())

    rig.controller.onClick(TrimSlot.ISSUE) // "No issue": no action

    rig.calls.shouldBeEmpty()
    rig.view.menus.shouldBeEmpty()
  }

  "R2: dispose unsubscribes; a disposed view is never rendered again" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()
    val before = rig.view.renders.size

    rig.controller.dispose()
    rig.controller.dispose()
    rig.state.settleBranch(withMr())
    rig.controller.onClick(TrimSlot.MR)

    rig.view.renders shouldHaveSize before
    rig.calls.shouldBeEmpty()
  }

  "a view disposed under the controller (trim rebuild) stops it at the next event" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()
    val before = rig.view.renders.size

    rig.view.isDisposed = true
    rig.state.settleBranch(withMr())
    rig.view.isDisposed = false
    rig.state.time.advanceBy(PERIOD)
    rig.state.settleBranch()

    rig.view.renders shouldHaveSize before
  }

  "a click after dispose does nothing, even on an item rendered before it" {
    val rig = TrimRig()
    rig.controller.attach(rig.state.state)
    rig.state.state.start()
    rig.state.settleBranch(withMr())
    rig.last.mr?.text shouldBe "!12"
    rig.last.pipeline?.action shouldBe TrimAction.ShowPipelineMenu

    rig.controller.dispose()
    rig.controller.onClick(TrimSlot.MR)
    rig.controller.onClick(TrimSlot.PIPELINE)

    rig.calls.shouldBeEmpty()
    rig.view.menus.shouldBeEmpty()
  }

  "R2: dispose unregisters the subscription" {
    var unregistered = 0
    val state = mockk<WindowCurrentBranchState>(relaxed = true)
    every { state.addListener(any()) } returns Registration { unregistered++ }
    every { state.snapshot } returns CurrentBranchSnapshot(0, null, null, null, false)
    val rig = TrimRig()

    rig.controller.attach(state)
    rig.controller.dispose()

    unregistered shouldBe 1
  }

  "R19: every trim icon maps to its own asset" {
    val expected = mapOf(
      TrimIcon.SUCCESS to "pipeline_success",
      TrimIcon.FAILED to "pipeline_failed",
      TrimIcon.RUNNING to "pipeline_running",
      TrimIcon.PENDING to "pipeline_pending",
      TrimIcon.CANCELED to "pipeline_canceled",
      TrimIcon.SKIPPED to "pipeline_skipped",
      TrimIcon.MANUAL to "pipeline_manual",
      TrimIcon.UNKNOWN to "pipeline_unknown",
      TrimIcon.INFO_LOADING to "pipeline_loading",
      TrimIcon.NO_PIPELINE to "pipeline_none",
      TrimIcon.MR_OPEN to "mr_open",
      TrimIcon.MR_DRAFT to "mr_draft",
      TrimIcon.MR_CREATE to "mr_create",
      TrimIcon.ISSUE to "issue_open",
    )
    expected.keys shouldBe TrimIcon.entries.toSet()
    TrimIcon.entries.forEach { trimIconName(it) shouldBe expected.getValue(it) }
    TrimIcon.entries.map { trimIconName(it) }.toSet() shouldHaveSize TrimIcon.entries.size
  }
})

class TrimDifferTest : StringSpec({
  val pipeline = TrimItemModel(TrimIcon.SUCCESS, "Pipeline passed", "Pipeline #7", TrimAction.ShowPipelineMenu)
  val mr = TrimItemModel(TrimIcon.MR_OPEN, "!12", "title", TrimAction.OpenUrl("u"))

  "the first render changes every slot and lays out" {
    TrimDiffer.diff(null, TrimModel(pipeline, null, null)) shouldBe
      TrimDiff(setOf(TrimSlot.PIPELINE, TrimSlot.MR, TrimSlot.ISSUE), relayout = true)
  }

  "an equal model changes nothing" {
    TrimDiffer.diff(TrimModel(pipeline, mr, null), TrimModel(pipeline, mr.copy(), null)).isEmpty shouldBe true
  }

  "an icon or tooltip change re-renders that slot without a re-layout" {
    val next = TrimModel(pipeline.copy(icon = TrimIcon.FAILED, tooltip = "Pipeline #8"), mr, null)

    TrimDiffer.diff(TrimModel(pipeline, mr, null), next) shouldBe TrimDiff(setOf(TrimSlot.PIPELINE), relayout = false)
  }

  "a text change or an item appearing or disappearing lays out" {
    val failed = TrimModel(pipeline.copy(text = "Pipeline failed"), mr, null)
    TrimDiffer.diff(TrimModel(pipeline, mr, null), failed) shouldBe TrimDiff(setOf(TrimSlot.PIPELINE), relayout = true)
    TrimDiffer.diff(TrimModel(pipeline, mr, null), TrimModel(pipeline, null, null)) shouldBe
      TrimDiff(setOf(TrimSlot.MR), relayout = true)
  }
})

/** The R1 width recipe's guards, over a fake tool bar. */
private class FakeWidthHost(
  override val isDisposed: Boolean = false,
  val item: FakeItem? = FakeItem(),
  val preferred: Int = 120,
) : TrimWidthHost {
  class FakeItem(override var width: Int = 40) : TrimWidthItem

  var relayouts = 0

  override fun ownItem(): TrimWidthItem? = item

  override fun preferredWidth(): Int = preferred

  override fun relayoutTrim() {
    relayouts++
  }
}

class TrimWidthTest : StringSpec({
  "R1: the tool item takes the control's preferred width and the trim re-tiles" {
    val host = FakeWidthHost()
    val logs = mutableListOf<String>()

    TrimWidth(log = { logs += it }).apply(host) shouldBe true

    host.item?.width shouldBe 120
    host.relayouts shouldBe 1
    logs.shouldBeEmpty()
  }

  "a disposed control is skipped silently" {
    val host = FakeWidthHost(isDisposed = true)

    TrimWidth(log = {}).apply(host) shouldBe false

    host.relayouts shouldBe 0
  }

  "no owning tool item (not in a tool bar, or not found) is skipped and logged once" {
    val logs = mutableListOf<String>()
    val width = TrimWidth(log = { logs += it })

    width.apply(FakeWidthHost(item = null)) shouldBe false
    width.apply(FakeWidthHost(item = null)) shouldBe false

    logs shouldContainExactly listOf("Current-branch trim: no tool item to resize")
  }

  "a host that throws is contained and logged by class once" {
    val logs = mutableListOf<String>()
    val width = TrimWidth(log = { logs += it })
    val throwing = object : TrimWidthHost {
      override val isDisposed = false

      override fun ownItem(): TrimWidthItem = error("/home/alice")

      override fun preferredWidth() = 0

      override fun relayoutTrim() = Unit
    }

    width.apply(throwing) shouldBe false
    width.apply(throwing) shouldBe false

    logs shouldContainExactly listOf("Current-branch trim resize failed: java.lang.IllegalStateException")
  }
})
