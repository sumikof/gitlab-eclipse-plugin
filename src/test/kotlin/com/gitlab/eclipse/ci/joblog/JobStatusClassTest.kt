package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private data class ClassCase(val status: String?, val expected: JobStatusClass, val waitMs: Long)

private val CLASS_CASES = listOf(
  ClassCase("running", JobStatusClass.LIVE, 5_000),
  ClassCase("canceling", JobStatusClass.LIVE, 5_000),
  ClassCase("created", JobStatusClass.NOT_STARTED, 5_000),
  ClassCase("pending", JobStatusClass.NOT_STARTED, 5_000),
  ClassCase("waiting_for_resource", JobStatusClass.NOT_STARTED, 5_000),
  ClassCase("preparing", JobStatusClass.NOT_STARTED, 5_000),
  ClassCase("manual", JobStatusClass.NOT_STARTED, 15_000),
  ClassCase("scheduled", JobStatusClass.NOT_STARTED, 15_000),
  ClassCase("success", JobStatusClass.TERMINAL, 5_000),
  ClassCase("failed", JobStatusClass.TERMINAL, 5_000),
  ClassCase("canceled", JobStatusClass.TERMINAL, 5_000),
  ClassCase("skipped", JobStatusClass.TERMINAL, 5_000),
  ClassCase(null, JobStatusClass.TERMINAL, 5_000),
  ClassCase("", JobStatusClass.TERMINAL, 5_000),
  ClassCase("Running", JobStatusClass.TERMINAL, 5_000),
  ClassCase("bogus", JobStatusClass.TERMINAL, 5_000),
)

class JobStatusClassTest : DescribeSpec({
  describe("classifyJobStatus and waitIntervalMs") {
    withData(nameFn = { "status=${it.status}" }, CLASS_CASES) { case ->
      classifyJobStatus(case.status) shouldBe case.expected
      waitIntervalMs(case.status) shouldBe case.waitMs
    }
  }

  describe("hasTrace") {
    it("needs a start and no erase") {
      hasTrace("2024-01-01T00:00:00Z", null) shouldBe true
      hasTrace(null, null) shouldBe false
      hasTrace("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z") shouldBe false
      hasTrace(null, "2024-01-02T00:00:00Z") shouldBe false
    }
  }
})
