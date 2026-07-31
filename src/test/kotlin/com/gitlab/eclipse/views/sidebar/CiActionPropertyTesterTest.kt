package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp0"

class CiActionPropertyTesterTest : StringSpec({
  val tester = CiActionPropertyTester()

  fun pipelineNode(
    canRetry: Boolean = false,
    canCancel: Boolean = false,
    projectId: Long? = 42L,
  ) = PipelineNode(
    GitLabPipeline(id = 1, status = "failed", projectId = projectId),
    children = emptyList(),
    canRetry = canRetry,
    canCancel = canCancel,
    sourceInstanceUrl = SRC_URL,
    sourceAuthFingerprint = SRC_FP,
  )

  fun jobNode(
    status: String? = "success",
    allowFailure: Boolean? = null,
    projectId: Long? = 42L,
  ) = JobNode(
    GitLabJob(id = 1, name = "job", status = status, stage = "build", allowFailure = allowFailure),
    projectId = projectId,
    sourceInstanceUrl = SRC_URL,
    sourceAuthFingerprint = SRC_FP,
  )

  fun test(receiver: Any?, property: String) = tester.test(receiver, property, emptyArray<Any?>(), null)

  "pipeline with canRetry and projectId -> canRetry true, canPlay false" {
    val n = pipelineNode(canRetry = true)
    test(n, "canRetry") shouldBe true
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe false
  }
  "pipeline with canCancel independent of canRetry" {
    val n = pipelineNode(canRetry = false, canCancel = true)
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe true
    test(n, "canPlay") shouldBe false
  }
  "pipeline with null projectId -> all false even when flags are set" {
    val n = pipelineNode(canRetry = true, canCancel = true, projectId = null)
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe false
  }

  "failed job -> canRetry only" {
    val n = jobNode(status = "failed")
    test(n, "canRetry") shouldBe true
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe false
  }
  "failed job allowed to fail -> still canRetry" {
    test(jobNode(status = "failed", allowFailure = true), "canRetry") shouldBe true
  }
  "running job -> canCancel only" {
    val n = jobNode(status = "running")
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe true
    test(n, "canPlay") shouldBe false
  }
  "manual job -> canPlay only" {
    val n = jobNode(status = "manual")
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe true
  }
  "skipped job -> all false" {
    val n = jobNode(status = "skipped")
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe false
  }
  "unknown-status job -> all false" {
    val n = jobNode(status = "definitely-not-a-status")
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe false
  }
  "job with null projectId -> all false even when status affords an action" {
    val n = jobNode(status = "failed", projectId = null)
    test(n, "canRetry") shouldBe false
    test(n, "canCancel") shouldBe false
    test(n, "canPlay") shouldBe false
    test(jobNode(status = "running", projectId = null), "canCancel") shouldBe false
    test(jobNode(status = "manual", projectId = null), "canPlay") shouldBe false
  }

  "unknown property name -> false" {
    test(pipelineNode(canRetry = true), "canDance") shouldBe false
  }
  "non-node receiver -> false" {
    test("a string", "canRetry") shouldBe false
    test(null, "canRetry") shouldBe false
  }
})
