package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.views.sidebar.CiActionPropertyTester
import com.gitlab.eclipse.views.sidebar.JobNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private val STATUSES = listOf(
  "created", "waiting_for_resource", "preparing", "pending", "running", "success", "failed", "canceled",
  "canceling", "skipped", "manual", "scheduled", "unknown-status", null,
)

/** [jobLogCanRetry]: the job log offers Retry exactly when the sidebar's Retry Job would (L4). */
class JobLogRetryEligibilityTest : StringSpec({
  "a finished or cancelling job can be retried; a waiting, running or skipped one cannot" {
    STATUSES.associateWith { jobLogCanRetry(disposed = false, status = it, inFlight = false, retried = false) } shouldBe
      mapOf(
        "created" to false,
        "waiting_for_resource" to false,
        "preparing" to false,
        "pending" to false,
        "running" to false,
        "success" to true,
        "failed" to true,
        "canceled" to true,
        "canceling" to true,
        "skipped" to false,
        "manual" to false,
        "scheduled" to false,
        "unknown-status" to false,
        null to false,
      )
  }

  "matches the sidebar's canRetry for a job with a project, status by status" {
    val tester = CiActionPropertyTester()
    STATUSES.forEach { status ->
      val node = JobNode(GitLabJob(id = 7, status = status, allowFailure = true), 42L, FAKE_URL, FAKE_FP)
      jobLogCanRetry(disposed = false, status = status, inFlight = false, retried = false) shouldBe
        tester.test(node, "canRetry", emptyArray(), null)
    }
  }

  "a retry in flight, a retry already done or a disposed session never offers Retry" {
    jobLogCanRetry(disposed = false, status = "failed", inFlight = true, retried = false) shouldBe false
    jobLogCanRetry(disposed = false, status = "failed", inFlight = false, retried = true) shouldBe false
    jobLogCanRetry(disposed = true, status = "failed", inFlight = false, retried = false) shouldBe false
  }
})
