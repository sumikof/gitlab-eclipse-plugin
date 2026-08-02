package com.gitlab.eclipse.ci.lint

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class CiLintGenerationRegistryTest : DescribeSpec({
  // The registry is a process-wide singleton: reset it around every test so no generation
  // numbers, epoch, or activation state leak between tests.
  beforeEach { CiLintGenerationRegistry.resetForTest() }
  afterEach { CiLintGenerationRegistry.resetForTest() }

  val k1 = CiLintKey("validateCiConfig", "https://a", "p", "src")
  val k2 = CiLintKey("validateCiConfig", "https://a", "p", "other-src")

  describe("nextGeneration") {
    it("assigns strictly increasing generations and records the last one as latest") {
      val g1 = CiLintGenerationRegistry.nextGeneration(k1)
      val g2 = CiLintGenerationRegistry.nextGeneration(k1)
      val g3 = CiLintGenerationRegistry.nextGeneration(k1)

      (g1 < g2) shouldBe true
      (g2 < g3) shouldBe true
      CiLintGenerationRegistry.isLatest(k1, g3) shouldBe true
    }

    it("is globally monotonic across keys (a generation number is never reused)") {
      val g1 = CiLintGenerationRegistry.nextGeneration(k1)
      val g2 = CiLintGenerationRegistry.nextGeneration(k2)
      val g3 = CiLintGenerationRegistry.nextGeneration(k1)

      (g1 < g2) shouldBe true
      (g2 < g3) shouldBe true
    }
  }

  describe("isLatest (response-order reversal)") {
    it("an earlier run that completes later is not latest; the newest run is") {
      val g1 = CiLintGenerationRegistry.nextGeneration(k1)
      val g2 = CiLintGenerationRegistry.nextGeneration(k1)

      CiLintGenerationRegistry.isLatest(k1, g1) shouldBe false
      CiLintGenerationRegistry.isLatest(k1, g2) shouldBe true
    }

    it("is false for a generation never assigned to the key") {
      CiLintGenerationRegistry.isLatest(k1, 99L) shouldBe false
    }

    it("keys are independent: a new generation on one key does not supersede another") {
      val g1 = CiLintGenerationRegistry.nextGeneration(k1)
      CiLintGenerationRegistry.nextGeneration(k2)

      CiLintGenerationRegistry.isLatest(k1, g1) shouldBe true
    }
  }

  describe("shouldAct") {
    it("supersede gate: false for the superseded run, true for the latest") {
      val g1 = CiLintGenerationRegistry.nextGeneration(k1)
      val g2 = CiLintGenerationRegistry.nextGeneration(k1)

      CiLintGenerationRegistry.shouldAct(k1, g1) shouldBe false
      CiLintGenerationRegistry.shouldAct(k1, g2) shouldBe true
    }

    it("stopped-activation gate: false when inactive even for the latest generation") {
      val g = CiLintGenerationRegistry.nextGeneration(k1)
      CiLintGenerationRegistry.onDeactivate()

      CiLintGenerationRegistry.isLatest(k1, g) shouldBe true
      CiLintGenerationRegistry.shouldAct(k1, g) shouldBe false
    }
  }

  describe("onDeactivate / onActivate lifecycle") {
    it("onDeactivate sets active=false; onActivate restores active=true") {
      CiLintGenerationRegistry.onDeactivate()
      CiLintGenerationRegistry.active shouldBe false

      CiLintGenerationRegistry.onActivate()
      CiLintGenerationRegistry.active shouldBe true
    }

    it("onActivate clears latest so an in-flight generation from before stop never reflects") {
      val gen = CiLintGenerationRegistry.nextGeneration(k1)

      CiLintGenerationRegistry.onActivate()

      CiLintGenerationRegistry.shouldAct(k1, gen) shouldBe false
    }

    it("onActivate bumps currentEpoch by exactly one") {
      val before = CiLintGenerationRegistry.currentEpoch

      CiLintGenerationRegistry.onActivate()

      CiLintGenerationRegistry.currentEpoch shouldBe before + 1
    }

    it("counter stays monotonic across onActivate (no ABA): a post-activate generation exceeds a pre-activate one") {
      val gen = CiLintGenerationRegistry.nextGeneration(k1)

      CiLintGenerationRegistry.onActivate()
      val gen2 = CiLintGenerationRegistry.nextGeneration(k1)

      (gen2 > gen) shouldBe true
      CiLintGenerationRegistry.shouldAct(k1, gen2) shouldBe true
    }
  }

  describe("currentEpoch") {
    it("starts at zero after resetForTest") {
      CiLintGenerationRegistry.currentEpoch shouldBe 0L
    }

    it("increments by one per onActivate call") {
      CiLintGenerationRegistry.onActivate()
      CiLintGenerationRegistry.currentEpoch shouldBe 1L

      CiLintGenerationRegistry.onActivate()
      CiLintGenerationRegistry.currentEpoch shouldBe 2L
    }
  }
})
