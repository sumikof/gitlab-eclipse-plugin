package com.gitlab.eclipse.currentbranch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** CI-03 cross-window dedupe (R18): bounded, null-safe, one popup per transition. */
class PipelineNotificationDedupeTest : StringSpec({
  val url = "https://gl.example.com"

  "the first request is shown, an identical second one is not" {
    val d = PipelineNotificationDedupe()
    d.shouldShow(url, 1, 10, "success") shouldBe true
    d.shouldShow(url, 1, 10, "success") shouldBe false
  }

  "a different status of the same pipeline is shown again, including a repeat after a retry" {
    val d = PipelineNotificationDedupe()
    d.shouldShow(url, 1, 10, "failed") shouldBe true
    d.shouldShow(url, 1, 10, "success") shouldBe true
    d.shouldShow(url, 1, 10, "failed") shouldBe true
  }

  "instance, project and pipeline all take part in the key" {
    val d = PipelineNotificationDedupe()
    d.shouldShow(url, 1, 10, "success") shouldBe true
    d.shouldShow("https://other.example.com", 1, 10, "success") shouldBe true
    d.shouldShow(url, 2, 10, "success") shouldBe true
    d.shouldShow(url, 1, 11, "success") shouldBe true
  }

  "null project and null status are valid keys" {
    val d = PipelineNotificationDedupe()
    d.shouldShow(url, null, 10, null) shouldBe true
    d.shouldShow(url, null, 10, null) shouldBe false
    d.shouldShow(url, null, 10, "success") shouldBe true
    d.shouldShow(url, 1, 10, null) shouldBe true
  }

  "the set is bounded: beyond capacity the oldest entry is forgotten" {
    val d = PipelineNotificationDedupe(capacity = 2)
    d.shouldShow(url, 1, 1, "s") shouldBe true
    d.shouldShow(url, 1, 2, "s") shouldBe true
    d.shouldShow(url, 1, 3, "s") shouldBe true // evicts pipeline 1
    d.shouldShow(url, 1, 1, "s") shouldBe true // forgotten
    d.shouldShow(url, 1, 3, "s") shouldBe false // still remembered
  }

  "eviction is least-recently-used, not first-inserted" {
    val d = PipelineNotificationDedupe(capacity = 2)
    d.shouldShow(url, 1, 1, "s") shouldBe true
    d.shouldShow(url, 1, 2, "s") shouldBe true
    d.shouldShow(url, 1, 1, "s") shouldBe false
    d.shouldShow(url, 1, 3, "s") shouldBe true
    d.shouldShow(url, 1, 1, "s") shouldBe false
    d.shouldShow(url, 1, 2, "s") shouldBe true
  }
})
