package com.gitlab.eclipse.ci

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private fun e(status: String?, allowFailure: Boolean = false, id: Long = 1) =
  JobEntry(GitLabJob(id = id, status = status, allowFailure = allowFailure), null)

private fun b(status: String?, allowFailure: Boolean = false, id: Long = 2) =
  BridgeEntry(GitLabBridge(id = id, status = status, allowFailure = allowFailure))

private val STATUSES = listOf(
  "manual", "success", "created", "waiting_for_resource", "preparing", "pending",
  "scheduled", "skipped", "canceled", "canceling", "failed", "running",
)

class StageStatusTest : DescribeSpec({
  describe("aggregateStatusName") {
    describe("single entry yields CiStatus.displayName") {
      withData(nameFn = { it }, STATUSES) { s ->
        aggregateStatusName(listOf(e(s))) shouldBe CiStatus.displayName(s, false)
      }
    }

    describe("the highest-priority status wins") {
      withData(
        nameFn = { (a, b) -> "$a vs $b" },
        "success" to "failed",
        "failed" to "running",
        "pending" to "created",
        "skipped" to "scheduled",
        "canceling" to "canceled",
        "manual" to "success",
      ) { (a, b) ->
        val winner = if (CiStatus.priority(a) >= CiStatus.priority(b)) a else b
        aggregateStatusName(listOf(e(a, id = 1), e(b, id = 2))) shouldBe CiStatus.displayName(winner)
        aggregateStatusName(listOf(e(b, id = 1), e(a, id = 2))) shouldBe CiStatus.displayName(winner)
      }
    }

    it("allowed failure (2) loses to created (3) but beats success (1)") {
      aggregateStatusName(listOf(e("failed", true), e("created"))) shouldBe "Created"
      aggregateStatusName(listOf(e("success"), e("failed", true))) shouldBe "Failed (allowed to fail)"
    }

    it("failed beats allowed failure") {
      aggregateStatusName(listOf(e("failed", true), e("failed", false))) shouldBe "Failed"
    }

    it("ties take the first entry") {
      aggregateStatusName(listOf(e("manual"), e(null), e("bogus"))) shouldBe "Manual"
      aggregateStatusName(listOf(e(null), e("manual"))) shouldBe "Status Unknown"
    }

    it("unknown status is Status Unknown, empty list too") {
      aggregateStatusName(listOf(e("what"))) shouldBe "Status Unknown"
      aggregateStatusName(emptyList()) shouldBe "Status Unknown"
    }
  }

  describe("bridges take part in the aggregate") {
    it("a failed bridge beats a passing job") {
      aggregateStatusName(listOf(e("success"), b("failed"))) shouldBe "Failed"
    }

    it("a running bridge beats a failed job") {
      aggregateStatusName(listOf(e("failed"), b("running"))) shouldBe "Running"
    }

    it("a failed bridge with allow_failure reads as allowed to fail, above a passing job") {
      aggregateStatusName(listOf(e("success"), b("failed", true))) shouldBe "Failed (allowed to fail)"
    }

    it("a failed bridge without allow_failure beats a failed job with allow_failure") {
      aggregateStatusName(listOf(e("failed", true), b("failed", false))) shouldBe "Failed"
    }
  }

  describe("priority order (design 3.4, literal)") {
    // Highest first; each adjacent pair must resolve to the earlier name regardless of entry order.
    val order = listOf(
      Triple("running", false, "Running"),
      Triple("failed", false, "Failed"),
      Triple("canceling", false, "Cancelling"),
      Triple("canceled", false, "Cancelled"),
      Triple("skipped", false, "Skipped"),
      Triple("scheduled", false, "Delayed"),
      Triple("pending", false, "Pending"),
      Triple("preparing", false, "Preparing"),
      Triple("waiting_for_resource", false, "Waiting for resource"),
      Triple("created", false, "Created"),
      Triple("failed", true, "Failed (allowed to fail)"),
      Triple("success", false, "Passed"),
      Triple("manual", false, "Manual"),
    )
    withData(nameFn = { (hi, lo) -> "${hi.third} > ${lo.third}" }, order.zipWithNext()) { (hi, lo) ->
      aggregateStatusName(listOf(e(hi.first, hi.second, 1), e(lo.first, lo.second, 2))) shouldBe hi.third
      aggregateStatusName(listOf(e(lo.first, lo.second, 1), e(hi.first, hi.second, 2))) shouldBe hi.third
    }

    it("unknown ties with manual at 0 and the first one wins") {
      aggregateStatusName(listOf(e("bogus"), e("manual"))) shouldBe "Status Unknown"
      aggregateStatusName(listOf(e("manual"), e("bogus"))) shouldBe "Manual"
    }
  }

  describe("aggregateStatus") {
    it("returns the status/allowFailure pair with max priority, first on ties") {
      aggregateStatus(listOf("success" to false, "failed" to true, "running" to false)) shouldBe ("running" to false)
      aggregateStatus(listOf("failed" to true, "success" to false)) shouldBe ("failed" to true)
      aggregateStatus(listOf("manual" to false, null to false)) shouldBe ("manual" to false)
    }

    it("is null/false for an empty list") {
      aggregateStatus(emptyList()) shouldBe (null to false)
    }
  }
})
