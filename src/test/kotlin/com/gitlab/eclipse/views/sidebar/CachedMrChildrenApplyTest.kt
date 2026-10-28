package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CachedMrChildrenApplyTest : StringSpec({
  "a node not yet applied, in the current refresh generation, takes the cached children" {
    shouldApplyCachedMrChildren(alreadyApplied = false, generationCurrent = true) shouldBe true
  }

  "a node another queued apply already filled is left alone, so its discussions are prefetched once" {
    shouldApplyCachedMrChildren(alreadyApplied = true, generationCurrent = true) shouldBe false
  }

  "a node from a refresh generation that has since been replaced is never filled" {
    shouldApplyCachedMrChildren(alreadyApplied = false, generationCurrent = false) shouldBe false
    shouldApplyCachedMrChildren(alreadyApplied = true, generationCurrent = false) shouldBe false
  }
})
