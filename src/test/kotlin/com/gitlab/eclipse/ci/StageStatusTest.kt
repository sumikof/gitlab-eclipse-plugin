package com.gitlab.eclipse.ci

import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private fun e(status: String?, allowFailure: Boolean = false, id: Long = 1) =
  JobEntry(GitLabJob(id = id, status = status, allowFailure = allowFailure), null)

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
