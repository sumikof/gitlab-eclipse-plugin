package com.gitlab.eclipse.currentbranch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** CI-03 (design §4.7, D3): notify only on a status change of an already observed pipeline id. */
class PipelineChangeDetectorTest : StringSpec({
  "the first observation is silent" {
    PipelineChangeDetector().observe("/g", 1, "running") shouldBe false
  }

  "the same status again is silent" {
    val d = PipelineChangeDetector()
    d.observe("/g", 1, "running")
    d.observe("/g", 1, "running") shouldBe false
  }

  "a different status of the same pipeline notifies, once" {
    val d = PipelineChangeDetector()
    d.observe("/g", 1, "running")
    d.observe("/g", 1, "success") shouldBe true
    d.observe("/g", 1, "success") shouldBe false
  }

  "a null status is a status like any other" {
    val d = PipelineChangeDetector()
    d.observe("/g", 1, null)
    d.observe("/g", 1, "success") shouldBe true
  }

  "a new pipeline id on the same repository is silent and replaces the memory" {
    val d = PipelineChangeDetector()
    d.observe("/g", 1, "success")
    d.observe("/g", 2, "running") shouldBe false
    d.observe("/g", 2, "success") shouldBe true
    d.observe("/g", 1, "success") shouldBe false // old id was forgotten: first observation again
  }

  "repositories are tracked independently" {
    val d = PipelineChangeDetector()
    d.observe("/g1", 1, "running")
    d.observe("/g2", 1, "running") shouldBe false
    d.observe("/g1", 1, "failed") shouldBe true
  }

  "ignore leaves the memory untouched so a change across an error still notifies" {
    val d = PipelineChangeDetector()
    d.observe("/g", 1, "running")
    d.ignore()
    d.observe("/g", 1, "failed") shouldBe true
  }

  "memory is bounded: the least recently observed repository is forgotten" {
    val d = PipelineChangeDetector(maxRepositories = 2)
    d.observe("/a", 1, "running")
    d.observe("/b", 1, "running")
    d.observe("/c", 1, "running") // evicts /a
    d.observe("/a", 1, "success") shouldBe false // forgotten -> first observation
    d.observe("/c", 1, "success") shouldBe true
  }
})
