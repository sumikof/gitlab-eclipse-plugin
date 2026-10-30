package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalTime

private val CHECKED = LocalTime.of(9, 5, 7)

private data class PageCase(val name: String, val page: JobLogPage, val expected: StatusPageModel)

private fun waiting(status: String?) = JobLogPage.Waiting(status, CHECKED)

private fun live(title: String, description: String, button: PageButton) =
  StatusPageModel(title, description, "Last checked at 09:05:07", button)

private fun plain(title: String) = StatusPageModel(title, "", null, null)

// L4: a job that finished without a log can be retried from its page, as in VS Code.
private fun retryable(title: String) = StatusPageModel(title, "", null, PageButton.RETRY)

private val CASES = listOf(
  PageCase(
    "manual",
    waiting("manual"),
    live("This job requires a manual action", "This job requires manual intervention to start.", PageButton.PLAY),
  ),
  PageCase(
    "created",
    waiting("created"),
    live(
      "This job has not been triggered yet",
      "This job depends on upstream jobs that need to succeed in order for this job to be triggered",
      PageButton.CANCEL,
    ),
  ),
  PageCase(
    "waiting_for_resource (L-B2-8)",
    waiting("waiting_for_resource"),
    live("Waiting for resource", "", PageButton.CANCEL),
  ),
  PageCase(
    "preparing",
    waiting("preparing"),
    live(
      "This job is preparing to start",
      "This job is performing tasks that must complete before it can start",
      PageButton.CANCEL,
    ),
  ),
  PageCase(
    "pending",
    waiting("pending"),
    live(
      "This job has not started yet",
      "This job is in pending state and is waiting to be picked by a runner",
      PageButton.CANCEL,
    ),
  ),
  PageCase(
    "scheduled",
    waiting("scheduled"),
    live(
      "This is a delayed job",
      "This job will automatically run after its timer finishes. Often they are used for incremental roll-out " +
        "deploys to production environments. When unscheduled it converts into a manual action.",
      PageButton.CANCEL,
    ),
  ),
  PageCase("skipped before it started (L-B2-5)", waiting("skipped"), plain("This job has been skipped")),
  PageCase("canceled before it started", waiting("canceled"), retryable("This job has been canceled")),
  PageCase("failed before it started", waiting("failed"), retryable("This job does not have a trace.")),
  PageCase("success without a trace", waiting("success"), retryable("This job does not have a trace.")),
  PageCase("canceling without a trace", waiting("canceling"), retryable("This job does not have a trace.")),
  PageCase("an unknown status", waiting("brand_new"), plain("This job does not have a trace.")),
  PageCase("no status", waiting(null), plain("This job does not have a trace.")),
  PageCase("erased", JobLogPage.Erased, plain("Job has been erased")),
  PageCase(
    "unavailable (L-B2-10)",
    JobLogPage.Unavailable("The job log was not found."),
    plain("The job log was not found."),
  ),
  PageCase("loading", JobLogPage.Loading, plain("Loading the job log…")),
)

/** [statusPageModel] (Task 12): the wording table of the brief, after VS Code `ci_status_metadata.ts`. */
class JobLogStatusPageModelTest : DescribeSpec({
  describe("statusPageModel") {
    withData(nameFn = { it.name }, CASES) { c ->
      statusPageModel(c.page) shouldBe c.expected
    }

    it("is null for the log") {
      statusPageModel(JobLogPage.Log).shouldBeNull()
    }

    it("formats the last check as 24-hour local time with seconds") {
      statusPageModel(JobLogPage.Waiting("pending", LocalTime.of(23, 0, 0)))?.lastChecked shouldBe
        "Last checked at 23:00:00"
    }

    it("leaves the time out while the job is waiting but the time is unknown") {
      statusPageModel(JobLogPage.Waiting("pending", null))?.lastChecked.shouldBeNull()
    }
  }

  // B2 review I-1: a rebuild the platform asks for is skipped only for the same kind of live page.
  describe("keepsStatusPage") {
    val waitingKind = JobLogPage.Waiting::class.java

    it("keeps a live page of the same kind, whatever changed inside it") {
      keepsStatusPage(waitingKind, pageAlive = true, next = waiting("manual")) shouldBe true
      keepsStatusPage(JobLogPage.Loading.javaClass, pageAlive = true, next = JobLogPage.Loading) shouldBe true
    }

    it("rebuilds for another kind, the log, a disposed or missing page, or no input") {
      keepsStatusPage(waitingKind, pageAlive = true, next = JobLogPage.Erased) shouldBe false
      keepsStatusPage(waitingKind, pageAlive = true, next = JobLogPage.Log) shouldBe false
      keepsStatusPage(waitingKind, pageAlive = false, next = waiting("manual")) shouldBe false
      keepsStatusPage(null, pageAlive = false, next = JobLogPage.Loading) shouldBe false
      keepsStatusPage(waitingKind, pageAlive = true, next = null) shouldBe false
    }
  }

  describe("PageButton") {
    it("names the VS Code buttons and the job actions of the sidebar") {
      PageButton.PLAY.label shouldBe "Trigger this manual action"
      PageButton.PLAY.action shouldBe "play"
      PageButton.CANCEL.label shouldBe "Cancel this job"
      PageButton.CANCEL.action shouldBe "cancel"
      PageButton.RETRY.label shouldBe "Retry this job"
      PageButton.RETRY.action shouldBe "retry"
    }
  }
})
