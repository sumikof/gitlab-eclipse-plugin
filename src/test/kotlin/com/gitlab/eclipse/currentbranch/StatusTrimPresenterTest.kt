package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.AmbiguousTags
import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSide
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Every row of the status-bar design §4.5 table, plus every CI status and the unknown fallback. */
class StatusTrimPresenterTest : StringSpec({
  fun snap(input: CurrentBranchSectionInput?, refreshing: Boolean = false) =
    CurrentBranchSnapshot(1, "/g", "/w", input, refreshing)

  fun shown(status: String? = "success", kind: PipelineKind = PipelineKind.BRANCH, id: Long = 7) =
    PipelineShown(
      PipelineSnapshot(
        GitLabPipeline(id = id, status = status),
        kind,
        Result.success(emptyList()),
        Result.success(emptyList()),
        "https://gl.example.com",
        "fp",
      ),
    )

  fun mr(iid: Long = 3, draft: Boolean = false) =
    GitLabMergeRequest(1, iid, "Title $iid", 42, "https://gl.example.com/mr/$iid", "opened", draft)

  fun issue(iid: Long, title: String = "Issue $iid") =
    GitLabIssue(iid, iid, title, "https://gl.example.com/i/$iid", "opened", null)

  fun ok(side: PipelineSide) = Result.success(side)
  fun info(m: GitLabMergeRequest?, vararg issues: GitLabIssue) = Result.success(CurrentBranchInfo(m, issues.toList()))
  val boom = Result.failure<Nothing>(IllegalStateException("x"))

  "nothing settled and nothing rendered shows the loading items" {
    val m = StatusTrimPresenter.present(snap(null), null)
    m.pipeline shouldBe TrimItemModel(TrimIcon.INFO_LOADING, "Fetching pipeline…", "Fetching pipeline…", TrimAction.None)
    m.mr?.text shouldBe "Finding MR…"
    m.mr?.icon shouldBe TrimIcon.INFO_LOADING
    m.issue shouldBe null
  }

  "placeholders on a fresh model also show the loading items" {
    val m = StatusTrimPresenter.present(snap(Resolved(null, null)), null)
    m.pipeline?.text shouldBe "Fetching pipeline…"
    m.mr?.text shouldBe "Finding MR…"
  }

  "R11 placeholders after a rendered model keep the previous model" {
    val prev = StatusTrimPresenter.present(snap(Resolved(info(mr(), issue(5)), ok(shown()))), null)
    StatusTrimPresenter.present(snap(Resolved(null, null), refreshing = true), prev) shouldBe prev
    StatusTrimPresenter.present(snap(null, refreshing = true), prev) shouldBe prev
  }

  "a pending side keeps its previous item while the other side settles" {
    val prev = StatusTrimPresenter.present(snap(Resolved(info(mr(), issue(5)), ok(shown("running")))), null)
    val m = StatusTrimPresenter.present(snap(Resolved(null, ok(shown("success")))), prev)
    m.pipeline?.text shouldBe "Pipeline Passed"
    m.mr shouldBe prev.mr
    m.issue shouldBe prev.issue
    val m2 = StatusTrimPresenter.present(snap(Resolved(info(null), null)), prev)
    m2.pipeline shouldBe prev.pipeline
    m2.mr?.text shouldBe "Create MR"
  }

  "a pending side without history shows its loading item" {
    StatusTrimPresenter.present(snap(Resolved(info(mr()), null)), null).pipeline?.text shouldBe "Fetching pipeline…"
    StatusTrimPresenter.present(snap(Resolved(null, ok(NoPipeline))), null).mr?.text shouldBe "Finding MR…"
  }

  "NoRepository hides everything" {
    StatusTrimPresenter.present(snap(NoRepository), null) shouldBe TrimModel(null, null, null)
    val prev = StatusTrimPresenter.present(snap(Resolved(info(mr()), ok(shown()))), null)
    StatusTrimPresenter.present(snap(NoRepository), prev) shouldBe TrimModel(null, null, null)
  }

  "branch with a pipeline shows status text, id tooltip and the menu action" {
    val p = StatusTrimPresenter.present(snap(Resolved(info(mr()), ok(shown("success", id = 99)))), null).pipeline
    p shouldBe TrimItemModel(TrimIcon.SUCCESS, "Pipeline Passed", "Pipeline #99", TrimAction.ShowPipelineMenu)
  }

  "branch without a pipeline shows No pipeline with the menu action" {
    val p = StatusTrimPresenter.present(snap(Resolved(info(mr()), ok(NoPipeline))), null).pipeline
    p shouldBe TrimItemModel(TrimIcon.NO_PIPELINE, "No pipeline", "No pipeline", TrimAction.ShowPipelineMenu)
  }

  "a tag pipeline shows the pipeline item only" {
    val m = StatusTrimPresenter.present(snap(Resolved(info(mr(), issue(1)), ok(shown(kind = PipelineKind.TAG)))), null)
    m.pipeline?.text shouldBe "Pipeline Passed"
    m.mr shouldBe null
    m.issue shouldBe null
  }

  "ambiguous tags hide everything" {
    StatusTrimPresenter.present(snap(Resolved(info(mr()), ok(AmbiguousTags))), null) shouldBe TrimModel(null, null, null)
  }

  "a failed pipeline side hides only the pipeline item" {
    val m = StatusTrimPresenter.present(snap(Resolved(info(mr(), issue(5)), boom)), null)
    m.pipeline shouldBe null
    m.mr?.text shouldBe "!3"
    m.issue?.text shouldBe "#5"
  }

  "a failed MR side hides MR and issue but keeps the pipeline" {
    val m = StatusTrimPresenter.present(snap(Resolved(boom, ok(shown()))), null)
    m.pipeline?.text shouldBe "Pipeline Passed"
    m.mr shouldBe null
    m.issue shouldBe null
  }

  "an open MR shows !iid with title tooltip and its url" {
    val m = StatusTrimPresenter.present(snap(Resolved(info(mr(12), issue(5)), ok(NoPipeline))), null).mr
    m shouldBe TrimItemModel(TrimIcon.MR_OPEN, "!12", "Title 12", TrimAction.OpenUrl("https://gl.example.com/mr/12"))
  }

  "a draft MR uses the draft icon" {
    StatusTrimPresenter.present(snap(Resolved(info(mr(draft = true)), ok(NoPipeline))), null).mr?.icon shouldBe TrimIcon.MR_DRAFT
  }

  "no open MR shows Create MR and hides the issue" {
    val m = StatusTrimPresenter.present(snap(Resolved(info(null), ok(NoPipeline))), null)
    m.mr?.icon shouldBe TrimIcon.MR_CREATE
    m.mr?.text shouldBe "Create MR"
    m.mr?.action shouldBe TrimAction.CreateMergeRequest
    m.issue shouldBe null
  }

  "an MR without closing issues shows an inert No issue" {
    val m = StatusTrimPresenter.present(snap(Resolved(info(mr()), ok(NoPipeline))), null).issue
    m?.text shouldBe "No issue"
    m?.action shouldBe TrimAction.None
  }

  "one closing issue shows #iid, its title and url" {
    val m = StatusTrimPresenter.present(snap(Resolved(info(mr(), issue(5, "Fix it")), ok(NoPipeline))), null).issue
    m shouldBe TrimItemModel(TrimIcon.ISSUE, "#5", "Fix it", TrimAction.OpenUrl("https://gl.example.com/i/5"))
  }

  "several closing issues show the first with an 'and N more' tooltip" {
    val issues = arrayOf(issue(5, "Fix it"), issue(6), issue(7))
    val input = Resolved(info(mr(), *issues), ok(NoPipeline))
    val m = StatusTrimPresenter.present(snap(input), null).issue
    m?.text shouldBe "#5"
    m?.tooltip shouldBe "Fix it and 2 more"
    m?.action shouldBe TrimAction.OpenUrl("https://gl.example.com/i/5")
  }

  "every CiStatus maps to its icon and display text" {
    val expected = mapOf(
      "success" to (TrimIcon.SUCCESS to "Passed"),
      "failed" to (TrimIcon.FAILED to "Failed"),
      "running" to (TrimIcon.RUNNING to "Running"),
      "pending" to (TrimIcon.PENDING to "Pending"),
      "created" to (TrimIcon.PENDING to "Created"),
      "preparing" to (TrimIcon.PENDING to "Preparing"),
      "waiting_for_resource" to (TrimIcon.PENDING to "Waiting for resource"),
      "scheduled" to (TrimIcon.PENDING to "Delayed"),
      "canceled" to (TrimIcon.CANCELED to "Cancelled"),
      "canceling" to (TrimIcon.CANCELED to "Cancelling"),
      "skipped" to (TrimIcon.SKIPPED to "Skipped"),
      "manual" to (TrimIcon.MANUAL to "Manual"),
    )
    expected.forEach { (status, want) ->
      val p = StatusTrimPresenter.present(snap(Resolved(info(null), ok(shown(status)))), null).pipeline
      p?.icon shouldBe want.first
      p?.text shouldBe "Pipeline ${want.second}"
    }
  }

  "an unknown or null status falls back to the UNKNOWN icon" {
    listOf("bogus", null).forEach { status ->
      val p = StatusTrimPresenter.present(snap(Resolved(info(null), ok(shown(status)))), null).pipeline
      p?.icon shouldBe TrimIcon.UNKNOWN
      p?.text shouldBe "Pipeline Status Unknown"
    }
  }
})
