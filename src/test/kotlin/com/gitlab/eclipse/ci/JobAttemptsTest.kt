package com.gitlab.eclipse.ci

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun job(id: Long, name: String?, stage: String? = "test", status: String = "success") =
  GitLabJob(id = id, name = name, stage = stage, status = status)

private fun bridge(id: Long, stage: String? = "trigger") =
  GitLabBridge(
    id = id,
    name = "b$id",
    stage = stage,
    status = "success",
  )

private fun names(order: List<Pair<String, List<StageEntry>>>): List<Pair<String, List<Long>>> =
  order.map { (stage, entries) -> stage to entries.map { it.id } }

class JobAttemptsTest : DescribeSpec({
  describe("keepLatestAttempts") {
    it("keeps a job with no retries and no firstAttemptId") {
      val r = keepLatestAttempts(listOf(job(1, "a")))
      r.map { it.job.id } shouldBe listOf(1L)
      r.map { it.firstAttemptId } shouldBe listOf(null)
    }

    it("keeps the max id and records the min id after one retry") {
      val r = keepLatestAttempts(listOf(job(3, "a"), job(1, "a")))
      r.map { it.job.id } shouldBe listOf(3L)
      r.map { it.firstAttemptId } shouldBe listOf(1L)
    }

    it("handles two retries") {
      val r = keepLatestAttempts(listOf(job(5, "a"), job(2, "a"), job(9, "a")))
      r.map { it.job.id } shouldBe listOf(9L)
      r.map { it.firstAttemptId } shouldBe listOf(2L)
    }

    it("never groups jobs without a name") {
      val r = keepLatestAttempts(listOf(job(1, null), job(2, null)))
      r.map { it.job.id } shouldBe listOf(1L, 2L)
      r.map { it.firstAttemptId } shouldBe listOf(null, null)
    }

    it("groups names independently") {
      val r = keepLatestAttempts(listOf(job(1, "a"), job(2, "b"), job(3, "a")))
      r.associate { it.job.name to (it.job.id to it.firstAttemptId) } shouldBe
        mapOf("a" to (3L to 1L), "b" to (2L to null))
    }

    it("sortId is firstAttemptId when present, else the job id") {
      JobEntry(job(7, "a"), 2L).sortId shouldBe 2L
      JobEntry(job(7, "a"), null).sortId shouldBe 7L
      BridgeEntry(bridge(4)).sortId shouldBe 4L
    }
  }

  describe("orderStageEntries") {
    val original = listOf(
      job(1, "build", "build"),
      job(2, "unit", "test"),
      job(3, "lint", "test"),
      job(4, "deploy", "deploy"),
    )

    it("orders by id and groups by stage in first-appearance order") {
      val order = orderStageEntries(keepLatestAttempts(original), emptyList())
      names(order) shouldBe listOf("build" to listOf(1L), "test" to listOf(2L, 3L), "deploy" to listOf(4L))
    }

    it("a retried job keeps its in-stage position (same name order before and after retry)") {
      val before = orderStageEntries(keepLatestAttempts(original), emptyList())
      val afterRetry = orderStageEntries(keepLatestAttempts(original + job(10, "unit", "test")), emptyList())
      afterRetry.map { it.first } shouldBe before.map { it.first }
      afterRetry.map { (_, e) -> e.map { (it as JobEntry).job.name } } shouldBe
        before.map { (_, e) -> e.map { (it as JobEntry).job.name } }
      afterRetry[1].second.map { it.id } shouldBe listOf(10L, 3L)
    }

    it("a stage holding only the retried job stays in place") {
      val jobs = original + job(10, "build", "build")
      val order = orderStageEntries(keepLatestAttempts(jobs), emptyList())
      order.map { it.first } shouldBe listOf("build", "test", "deploy")
    }

    it("a bridge sorts against the retried job's first attempt, not its latest id") {
      val jobs = keepLatestAttempts(listOf(job(2, "x", "test"), job(10, "x", "test")))
      val order = orderStageEntries(jobs, listOf(bridge(5, "test")))
      names(order) shouldBe listOf("test" to listOf(10L, 5L))
    }

    it("interleaves bridges by their own id and falls back to NO_STAGE") {
      val order = orderStageEntries(
        keepLatestAttempts(listOf(job(1, "a", "build"), job(5, "z", null))),
        listOf(bridge(3, "trigger"), bridge(4, null)),
      )
      names(order) shouldBe listOf("build" to listOf(1L), "trigger" to listOf(3L), NO_STAGE to listOf(4L, 5L))
    }
  }
})
