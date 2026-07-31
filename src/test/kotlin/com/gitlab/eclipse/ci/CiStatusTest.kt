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

  "contextAction maps manual to EXECUTABLE" {
    CiStatus.contextAction("manual") shouldBe CiAction.EXECUTABLE
  }
  "contextAction maps success to RETRYABLE" {
    CiStatus.contextAction("success") shouldBe CiAction.RETRYABLE
  }
  "contextAction maps created to CANCELLABLE" {
    CiStatus.contextAction("created") shouldBe CiAction.CANCELLABLE
  }
  "contextAction maps waiting_for_resource to CANCELLABLE" {
    CiStatus.contextAction("waiting_for_resource") shouldBe CiAction.CANCELLABLE
  }
  "contextAction maps preparing to CANCELLABLE" {
    CiStatus.contextAction("preparing") shouldBe CiAction.CANCELLABLE
  }
  "contextAction maps pending to CANCELLABLE" {
    CiStatus.contextAction("pending") shouldBe CiAction.CANCELLABLE
  }
  "contextAction maps scheduled to CANCELLABLE" {
    CiStatus.contextAction("scheduled") shouldBe CiAction.CANCELLABLE
  }
  "contextAction maps skipped to null" {
    CiStatus.contextAction("skipped") shouldBe null
  }
  "contextAction maps canceled to RETRYABLE" {
    CiStatus.contextAction("canceled") shouldBe CiAction.RETRYABLE
  }
  "contextAction maps canceling to RETRYABLE" {
    CiStatus.contextAction("canceling") shouldBe CiAction.RETRYABLE
  }
  "contextAction maps failed to RETRYABLE" {
    CiStatus.contextAction("failed") shouldBe CiAction.RETRYABLE
  }
  "contextAction maps running to CANCELLABLE" {
    CiStatus.contextAction("running") shouldBe CiAction.CANCELLABLE
  }
  "contextAction falls back to null for an unrecognized status" {
    CiStatus.contextAction("not_a_real_status") shouldBe null
  }
  "contextAction falls back to null for null status (never-throw)" {
    CiStatus.contextAction(null) shouldBe null
  }
  "contextAction keeps failed+allowFailure=true as RETRYABLE" {
    CiStatus.contextAction("failed", allowFailure = true) shouldBe CiAction.RETRYABLE
  }
  "contextAction keeps failed+allowFailure=false as RETRYABLE" {
    CiStatus.contextAction("failed", allowFailure = false) shouldBe CiAction.RETRYABLE
  }
  "contextAction ignores allowFailure for a non-failed status" {
    CiStatus.contextAction("success", allowFailure = true) shouldBe CiAction.RETRYABLE
  }
})
