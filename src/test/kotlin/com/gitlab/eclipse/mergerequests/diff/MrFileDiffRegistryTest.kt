package com.gitlab.eclipse.mergerequests.diff

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun key(head: String = "b".repeat(40), path: String = "a.kt") =
  MrFileDiffKey("https://gl.example", 42L, 7L, "a".repeat(40), head, path, path)

class MrFileDiffRegistryTest : DescribeSpec({
  beforeEach { MrFileDiffRegistry.resetForTest() }
  afterSpec { MrFileDiffRegistry.resetForTest() }

  describe("begin") {
    it("numbers a new request and makes it current") {
      val gen = MrFileDiffRegistry.begin(key()).shouldNotBeNull()

      MrFileDiffRegistry.isCurrent(key(), gen) shouldBe true
    }

    it("dedupes the identical key while it is in flight") {
      MrFileDiffRegistry.begin(key()).shouldNotBeNull()

      MrFileDiffRegistry.begin(key()).shouldBeNull()
    }

    it("accepts the identical key again once it finished") {
      val gen = MrFileDiffRegistry.begin(key()).shouldNotBeNull()
      MrFileDiffRegistry.finish(key(), gen)

      MrFileDiffRegistry.begin(key()).shouldNotBeNull()
    }

    it("refuses while inactive") {
      MrFileDiffRegistry.onDeactivate()

      MrFileDiffRegistry.begin(key()).shouldBeNull()
    }
  }

  describe("isCurrent") {
    it("a new snapshot of the same file supersedes the older generation") {
      val old = MrFileDiffRegistry.begin(key(head = "c".repeat(40))).shouldNotBeNull()
      val new = MrFileDiffRegistry.begin(key(head = "d".repeat(40))).shouldNotBeNull()

      MrFileDiffRegistry.isCurrent(key(head = "c".repeat(40)), old) shouldBe false
      MrFileDiffRegistry.isCurrent(key(head = "d".repeat(40)), new) shouldBe true
    }

    it("the superseded snapshot can be requested again (it is no longer deduped)") {
      MrFileDiffRegistry.begin(key(head = "c".repeat(40))).shouldNotBeNull()
      MrFileDiffRegistry.begin(key(head = "d".repeat(40))).shouldNotBeNull()

      MrFileDiffRegistry.begin(key(head = "c".repeat(40))).shouldNotBeNull()
    }

    it("different files are independent") {
      val a = MrFileDiffRegistry.begin(key(path = "a.kt")).shouldNotBeNull()
      val b = MrFileDiffRegistry.begin(key(path = "b.kt")).shouldNotBeNull()

      MrFileDiffRegistry.isCurrent(key(path = "a.kt"), a) shouldBe true
      MrFileDiffRegistry.isCurrent(key(path = "b.kt"), b) shouldBe true
    }

    it("is false after finish") {
      val gen = MrFileDiffRegistry.begin(key()).shouldNotBeNull()
      MrFileDiffRegistry.finish(key(), gen)

      MrFileDiffRegistry.isCurrent(key(), gen) shouldBe false
    }

    it("is false while inactive") {
      val gen = MrFileDiffRegistry.begin(key()).shouldNotBeNull()
      MrFileDiffRegistry.onDeactivate()

      MrFileDiffRegistry.active shouldBe false
      MrFileDiffRegistry.isCurrent(key(), gen) shouldBe false
    }
  }

  describe("onActivate") {
    it("invalidates generations numbered before it and restores active") {
      val gen = MrFileDiffRegistry.begin(key()).shouldNotBeNull()
      MrFileDiffRegistry.onDeactivate()
      MrFileDiffRegistry.onActivate()

      MrFileDiffRegistry.active shouldBe true
      MrFileDiffRegistry.isCurrent(key(), gen) shouldBe false
      val fresh = MrFileDiffRegistry.begin(key()).shouldNotBeNull()
      (fresh > gen) shouldBe true
      MrFileDiffRegistry.isCurrent(key(), fresh) shouldBe true
    }

    it("a stale finish after reactivation does not disturb the fresh generation") {
      val old = MrFileDiffRegistry.begin(key()).shouldNotBeNull()
      MrFileDiffRegistry.onActivate()
      val fresh = MrFileDiffRegistry.begin(key()).shouldNotBeNull()

      MrFileDiffRegistry.finish(key(), old)

      MrFileDiffRegistry.isCurrent(key(), fresh) shouldBe true
      MrFileDiffRegistry.begin(key()).shouldBeNull()
    }
  }
})
