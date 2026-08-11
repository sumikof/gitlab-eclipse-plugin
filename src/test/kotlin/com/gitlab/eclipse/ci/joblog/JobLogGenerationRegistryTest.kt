package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class JobLogGenerationRegistryTest : DescribeSpec({
  // The registry is a process-wide singleton: reset it around every test so no generation
  // numbers or activation state leak between tests.
  beforeEach { JobLogGenerationRegistry.resetForTest() }
  afterEach { JobLogGenerationRegistry.resetForTest() }

  val k1 = JobLogKey("conn-a", 1L, 10L)
  val k2 = JobLogKey("conn-b", 2L, 20L)

  describe("nextGeneration") {
    it("assigns strictly increasing generations and records the last one as latest") {
      val g1 = JobLogGenerationRegistry.nextGeneration(k1)
      val g2 = JobLogGenerationRegistry.nextGeneration(k1)
      val g3 = JobLogGenerationRegistry.nextGeneration(k1)

      (g1 < g2) shouldBe true
      (g2 < g3) shouldBe true
      JobLogGenerationRegistry.isLatest(k1, g3) shouldBe true
    }

    it("is globally monotonic across keys (a generation number is never reused)") {
      val g1 = JobLogGenerationRegistry.nextGeneration(k1)
      val g2 = JobLogGenerationRegistry.nextGeneration(k2)
      val g3 = JobLogGenerationRegistry.nextGeneration(k1)

      (g1 < g2) shouldBe true
      (g2 < g3) shouldBe true
    }
  }

  describe("isLatest (response-order reversal, design 14.4a)") {
    it("an earlier run that completes later is not latest; the newest run is") {
      val g1 = JobLogGenerationRegistry.nextGeneration(k1)
      val g2 = JobLogGenerationRegistry.nextGeneration(k1)

      // g1's response arrives AFTER g2 was launched: g1 must not reflect.
      JobLogGenerationRegistry.isLatest(k1, g1) shouldBe false
      JobLogGenerationRegistry.isLatest(k1, g2) shouldBe true
    }

    it("is false for a generation never assigned to the key") {
      JobLogGenerationRegistry.isLatest(k1, 99L) shouldBe false
    }

    it("keys are independent: a new generation on one key does not supersede another") {
      val g1 = JobLogGenerationRegistry.nextGeneration(k1)
      JobLogGenerationRegistry.nextGeneration(k2)

      JobLogGenerationRegistry.isLatest(k1, g1) shouldBe true
    }
  }

  describe("shouldAct") {
    it("supersede gate (design 14.4b): false for the superseded run, true for the latest") {
      val g1 = JobLogGenerationRegistry.nextGeneration(k1)
      val g2 = JobLogGenerationRegistry.nextGeneration(k1)

      JobLogGenerationRegistry.shouldAct(k1, g1) shouldBe false
      JobLogGenerationRegistry.shouldAct(k1, g2) shouldBe true
    }

    it("stopped-activation gate (design 14.4d): false when inactive even for the latest generation") {
      val g = JobLogGenerationRegistry.nextGeneration(k1)
      JobLogGenerationRegistry.active = false

      JobLogGenerationRegistry.isLatest(k1, g) shouldBe true
      JobLogGenerationRegistry.shouldAct(k1, g) shouldBe false
    }
  }

  describe("onActivate (stop -> start in the same class loader)") {
    it("restores active after the stop hook cleared it, so shouldAct can pass again") {
      JobLogGenerationRegistry.active = false

      JobLogGenerationRegistry.onActivate()

      JobLogGenerationRegistry.active shouldBe true
      val g = JobLogGenerationRegistry.nextGeneration(k1)
      JobLogGenerationRegistry.shouldAct(k1, g) shouldBe true
    }

    it("clears latest so an in-flight generation from before stop never reflects post-restart") {
      val gen = JobLogGenerationRegistry.nextGeneration(k1)
      JobLogGenerationRegistry.active = false

      JobLogGenerationRegistry.onActivate()

      JobLogGenerationRegistry.isLatest(k1, gen) shouldBe false
      JobLogGenerationRegistry.shouldAct(k1, gen) shouldBe false
    }

    it("clears latest for every key, not just the most recently used one") {
      val g1 = JobLogGenerationRegistry.nextGeneration(k1)
      val g2 = JobLogGenerationRegistry.nextGeneration(k2)

      JobLogGenerationRegistry.onActivate()

      JobLogGenerationRegistry.isLatest(k1, g1) shouldBe false
      JobLogGenerationRegistry.isLatest(k2, g2) shouldBe false
    }

    it("keeps counter monotonic (no ABA): a post-activate generation exceeds a pre-activate one") {
      val gen = JobLogGenerationRegistry.nextGeneration(k1)

      JobLogGenerationRegistry.onActivate()
      val gen2 = JobLogGenerationRegistry.nextGeneration(k1)

      (gen2 > gen) shouldBe true
      JobLogGenerationRegistry.isLatest(k1, gen) shouldBe false
      JobLogGenerationRegistry.shouldAct(k1, gen2) shouldBe true
    }
  }
})
