package com.gitlab.eclipse.ci.actions

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class InFlightWriteGuardTest : DescribeSpec({
  // The guard is a process-wide singleton: track every key acquired in a test and release
  // them afterwards so no test leaks in-flight state into the next.
  val acquired = mutableListOf<WriteKey>()

  fun tryAcquire(key: WriteKey): Boolean =
    InFlightWriteGuard.tryAcquire(key).also { if (it) acquired.add(key) }

  afterEach {
    acquired.forEach { InFlightWriteGuard.release(it) }
    acquired.clear()
  }

  describe("tryAcquire") {
    it("returns true first, false while held, and true again after release") {
      val key = WriteKey("https://gitlab.example.com", "pipeline", 42L)

      tryAcquire(key) shouldBe true
      tryAcquire(key) shouldBe false

      InFlightWriteGuard.release(key)
      acquired.clear()

      tryAcquire(key) shouldBe true
    }

    it("serializes two different actions on the same target (key excludes the action)") {
      // FR-6: a retry and a cancel on the same (instance, kind, id) build the SAME key,
      // so whichever comes second is rejected while the first is in flight.
      val keyForRetry = WriteKey("https://gitlab.example.com", "pipeline", 7L)
      val keyForCancel = WriteKey("https://gitlab.example.com", "pipeline", 7L)

      tryAcquire(keyForRetry) shouldBe true
      tryAcquire(keyForCancel) shouldBe false
    }

    it("treats a different targetId as an independent target") {
      tryAcquire(WriteKey("https://gitlab.example.com", "pipeline", 1L)) shouldBe true
      tryAcquire(WriteKey("https://gitlab.example.com", "pipeline", 2L)) shouldBe true
    }

    it("treats a different instanceUrl as an independent target") {
      tryAcquire(WriteKey("https://a.example.com", "pipeline", 1L)) shouldBe true
      tryAcquire(WriteKey("https://b.example.com", "pipeline", 1L)) shouldBe true
    }

    it("treats a different targetKind as an independent target") {
      tryAcquire(WriteKey("https://gitlab.example.com", "pipeline", 1L)) shouldBe true
      tryAcquire(WriteKey("https://gitlab.example.com", "job", 1L)) shouldBe true
    }
  }
})
