package com.gitlab.eclipse.ci

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CiStatusTest : StringSpec({
  "displayName maps manual" { CiStatus.displayName("manual") shouldBe "Manual" }
  "displayName maps success" { CiStatus.displayName("success") shouldBe "Passed" }
  "displayName maps created" { CiStatus.displayName("created") shouldBe "Created" }
  "displayName maps waiting_for_resource" {
    CiStatus.displayName("waiting_for_resource") shouldBe "Waiting for resource"
  }
  "displayName maps preparing" { CiStatus.displayName("preparing") shouldBe "Preparing" }
  "displayName maps pending" { CiStatus.displayName("pending") shouldBe "Pending" }
  "displayName maps scheduled" { CiStatus.displayName("scheduled") shouldBe "Delayed" }
  "displayName maps skipped" { CiStatus.displayName("skipped") shouldBe "Skipped" }
  "displayName maps canceled" { CiStatus.displayName("canceled") shouldBe "Cancelled" }
  "displayName maps canceling" { CiStatus.displayName("canceling") shouldBe "Cancelling" }
  "displayName maps failed" { CiStatus.displayName("failed") shouldBe "Failed" }
  "displayName maps running" { CiStatus.displayName("running") shouldBe "Running" }

  "displayName falls back to Status Unknown for an unrecognized status" {
    CiStatus.displayName("not_a_real_status") shouldBe "Status Unknown"
  }

  "displayName falls back to Status Unknown for null status (never-throw)" {
    CiStatus.displayName(null) shouldBe "Status Unknown"
  }

  "displayName reports the allow-to-fail special case for failed+allowFailure" {
    CiStatus.displayName("failed", allowFailure = true) shouldBe "Failed (allowed to fail)"
  }

  "displayName ignores allowFailure for non-failed statuses" {
    CiStatus.displayName("success", allowFailure = true) shouldBe "Passed"
  }

  "priority maps success" { CiStatus.priority("success") shouldBe 1 }
  "priority maps failed" { CiStatus.priority("failed") shouldBe 11 }
  "priority maps running" { CiStatus.priority("running") shouldBe 12 }
  "priority maps manual" { CiStatus.priority("manual") shouldBe 0 }

  "priority falls back to 0 for an unrecognized status" {
    CiStatus.priority("not_a_real_status") shouldBe 0
  }

  "priority falls back to 0 for null status (never-throw)" {
    CiStatus.priority(null) shouldBe 0
  }

  "priority reports the allow-to-fail special case for failed+allowFailure" {
    CiStatus.priority("failed", allowFailure = true) shouldBe 2
  }
})
