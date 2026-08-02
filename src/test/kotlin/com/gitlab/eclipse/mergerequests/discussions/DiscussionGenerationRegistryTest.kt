package com.gitlab.eclipse.mergerequests.discussions

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class DiscussionGenerationRegistryTest : DescribeSpec({
  // The registry is a process-wide singleton: reset it around every test so no generation
  // numbers, epoch, or activation state leak between tests.
  beforeEach { DiscussionGenerationRegistry.resetForTest() }
  afterEach { DiscussionGenerationRegistry.resetForTest() }

  val k1 = DiscussionKey.of("https://a", "fp-1", 1L, 10L)
  val k2 = DiscussionKey.of("https://a", "fp-1", 1L, 20L)

  describe("nextGeneration") {
    it("assigns strictly increasing generations and records the last one as latest") {
      val g1 = DiscussionGenerationRegistry.nextGeneration(k1)
      val g2 = DiscussionGenerationRegistry.nextGeneration(k1)
      val g3 = DiscussionGenerationRegistry.nextGeneration(k1)

      (g1 < g2) shouldBe true
      (g2 < g3) shouldBe true
      DiscussionGenerationRegistry.isLatest(k1, g3) shouldBe true
    }

    it("is globally monotonic across keys (a generation number is never reused)") {
      val g1 = DiscussionGenerationRegistry.nextGeneration(k1)
      val g2 = DiscussionGenerationRegistry.nextGeneration(k2)
      val g3 = DiscussionGenerationRegistry.nextGeneration(k1)

      (g1 < g2) shouldBe true
      (g2 < g3) shouldBe true
    }
  }

  describe("isLatest (response-order reversal)") {
    it("an earlier fetch that completes later is not latest; the newest fetch is") {
      val g1 = DiscussionGenerationRegistry.nextGeneration(k1)
      val g2 = DiscussionGenerationRegistry.nextGeneration(k1)

      DiscussionGenerationRegistry.isLatest(k1, g1) shouldBe false
      DiscussionGenerationRegistry.isLatest(k1, g2) shouldBe true
    }

    it("is false for a generation never assigned to the key") {
      DiscussionGenerationRegistry.isLatest(k1, 99L) shouldBe false
    }

    it("keys are independent: a new generation on one key does not supersede another") {
      val g1 = DiscussionGenerationRegistry.nextGeneration(k1)
      DiscussionGenerationRegistry.nextGeneration(k2)

      DiscussionGenerationRegistry.isLatest(k1, g1) shouldBe true
    }
  }

  describe("shouldAct") {
    it("supersede gate: false for the superseded fetch, true for the latest") {
      val g1 = DiscussionGenerationRegistry.nextGeneration(k1)
      val g2 = DiscussionGenerationRegistry.nextGeneration(k1)

      DiscussionGenerationRegistry.shouldAct(k1, g1) shouldBe false
      DiscussionGenerationRegistry.shouldAct(k1, g2) shouldBe true
    }

    it("stopped-activation gate: false when inactive even for the latest generation") {
      val g = DiscussionGenerationRegistry.nextGeneration(k1)
      DiscussionGenerationRegistry.onDeactivate()

      DiscussionGenerationRegistry.isLatest(k1, g) shouldBe true
      DiscussionGenerationRegistry.shouldAct(k1, g) shouldBe false
    }
  }

  describe("onDeactivate / onActivate lifecycle") {
    it("onDeactivate sets active=false; onActivate restores active=true") {
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.active shouldBe false

      DiscussionGenerationRegistry.onActivate()
      DiscussionGenerationRegistry.active shouldBe true
    }

    it("onActivate clears latest so an in-flight generation from before stop never reflects") {
      val gen = DiscussionGenerationRegistry.nextGeneration(k1)

      DiscussionGenerationRegistry.onActivate()

      DiscussionGenerationRegistry.shouldAct(k1, gen) shouldBe false
    }

    it("onActivate bumps currentEpoch by exactly one") {
      val before = DiscussionGenerationRegistry.currentEpoch

      DiscussionGenerationRegistry.onActivate()

      DiscussionGenerationRegistry.currentEpoch shouldBe before + 1
    }

    it("counter stays monotonic across onActivate (no ABA): a post-activate generation exceeds a pre-activate one") {
      val gen = DiscussionGenerationRegistry.nextGeneration(k1)

      DiscussionGenerationRegistry.onActivate()
      val gen2 = DiscussionGenerationRegistry.nextGeneration(k1)

      (gen2 > gen) shouldBe true
      DiscussionGenerationRegistry.shouldAct(k1, gen2) shouldBe true
    }
  }

  describe("currentEpoch") {
    it("starts at zero after resetForTest") {
      DiscussionGenerationRegistry.currentEpoch shouldBe 0L
    }

    it("increments by one per onActivate call") {
      DiscussionGenerationRegistry.onActivate()
      DiscussionGenerationRegistry.currentEpoch shouldBe 1L

      DiscussionGenerationRegistry.onActivate()
      DiscussionGenerationRegistry.currentEpoch shouldBe 2L
    }
  }

  describe("DiscussionKey.of (account-switch protection)") {
    it("same instance URL with different authFingerprint produces different keys whose generations do not interfere") {
      val accountA = DiscussionKey.of("https://a", "fp-A", 1L, 10L)
      val accountB = DiscussionKey.of("https://a", "fp-B", 1L, 10L)

      accountA shouldBe accountA
      (accountA == accountB) shouldBe false

      val genA = DiscussionGenerationRegistry.nextGeneration(accountA)
      val genB = DiscussionGenerationRegistry.nextGeneration(accountB)

      DiscussionGenerationRegistry.isLatest(accountA, genA) shouldBe true
      DiscussionGenerationRegistry.isLatest(accountB, genB) shouldBe true
    }
  }

  describe("DiscussionKey.of (instance URL normalization)") {
    it("a trailing-slash spelling of the same instance URL normalizes to the same key as the bare form") {
      val bare = DiscussionKey.of("https://gitlab.example.com", "fp-1", 1L, 10L)
      val trailingSlash = DiscussionKey.of("https://gitlab.example.com/", "fp-1", 1L, 10L)

      bare shouldBe trailingSlash
    }
  }

  describe("DiscussionKey.of (project / MR discrimination)") {
    it("different projectId values produce different keys") {
      val p1 = DiscussionKey.of("https://a", "fp-1", 1L, 10L)
      val p2 = DiscussionKey.of("https://a", "fp-1", 2L, 10L)

      (p1 == p2) shouldBe false
    }

    it("different mrIid values produce different keys") {
      val m1 = DiscussionKey.of("https://a", "fp-1", 1L, 10L)
      val m2 = DiscussionKey.of("https://a", "fp-1", 1L, 20L)

      (m1 == m2) shouldBe false
    }
  }
})
