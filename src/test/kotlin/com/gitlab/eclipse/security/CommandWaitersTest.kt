package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val PATH_A = "/w/a.kt"
private const val PATH_B = "/w/b.kt"

class CommandWaitersTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeEach {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
  }
  afterEach {
    CommandWaiters.resetForTest()
    DiagnosticGenerationRegistry.resetForTest()
  }

  fun epoch() = DiagnosticGenerationRegistry.currentEpoch

  describe("registration") {
    it("keeps one waiter per command and hands them out oldest first") {
      val first = CommandWaiters.add(PATH_A, epoch())!!
      val second = CommandWaiters.add(PATH_A, epoch())!!
      val third = CommandWaiters.add(PATH_A, epoch())!!

      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
      // The two oldest are gone, so only the third one is left to be cancelled by id.
      CommandWaiters.consumeById(first, epoch()) shouldBe false
      CommandWaiters.consumeById(second, epoch()) shouldBe false
      CommandWaiters.consumeById(third, epoch()) shouldBe true
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("hands out strictly increasing ids") {
      val ids = (1..5).map { CommandWaiters.add(PATH_A, epoch())!! }
      ids shouldBe ids.sorted()
      ids.toSet().size shouldBe ids.size
    }

    it("keeps paths apart") {
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.consumeOldest(PATH_B, epoch()) shouldBe WaiterMatch.NO_WAITER
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
    }
  }

  describe("consumeOldest") {
    it("removes exactly one waiter per call") {
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.add(PATH_A, epoch())

      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("reports NO_WAITER for a path nobody is waiting on") {
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }
  }

  describe("consumeById") {
    it("removes only its own waiter and leaves every other one alone") {
      // A's response arrives and consumes A's waiter, then B is registered on the same path.
      val a = CommandWaiters.add(PATH_A, epoch())!!
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
      val b = CommandWaiters.add(PATH_A, epoch())!!

      // A's deadline now fires. It must not take B's waiter with it.
      CommandWaiters.consumeById(a, epoch()) shouldBe false
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
      CommandWaiters.consumeById(b, epoch()) shouldBe false
    }

    it("is not idempotent by accident: a second call reports nothing was removed") {
      val id = CommandWaiters.add(PATH_A, epoch())!!
      CommandWaiters.consumeById(id, epoch()) shouldBe true
      CommandWaiters.consumeById(id, epoch()) shouldBe false
    }

    it("reports false for an id that was never handed out") {
      CommandWaiters.consumeById(999_999L, epoch()) shouldBe false
    }
  }

  describe("epoch isolation") {
    it("refuses to register against a stale epoch") {
      val stale = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      CommandWaiters.add(PATH_A, stale) shouldBe null
    }

    it("reports EPOCH_MISMATCH instead of consuming a waiter of the live connection") {
      DiagnosticGenerationRegistry.onServerStopped()
      val stale = epoch() - 1
      CommandWaiters.add(PATH_A, epoch()) shouldNotBe null

      // A response from the dead connection must not consume the live connection's waiter.
      CommandWaiters.consumeOldest(PATH_A, stale) shouldBe WaiterMatch.EPOCH_MISMATCH
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("refuses consumeById across epochs") {
      val id = CommandWaiters.add(PATH_A, epoch())!!
      DiagnosticGenerationRegistry.onServerStopped()

      CommandWaiters.consumeById(id, epoch() - 1) shouldBe false
      // The waiter belongs to the dead connection, so the live epoch cannot claim it either.
      CommandWaiters.consumeById(id, epoch()) shouldBe false
    }

    it("ignores markDeadlineArmed from a stale epoch") {
      val id = CommandWaiters.add(PATH_A, epoch())!!
      val stale = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      CommandWaiters.markDeadlineArmed(id, stale)
      CommandWaiters.isDeadlineArmed(id) shouldBe false
    }

    it("never lets a new connection consume a waiter left by the old one") {
      val stale = epoch()
      CommandWaiters.add(PATH_A, stale)
      DiagnosticGenerationRegistry.onServerStopped()

      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }
  }

  describe("deadline bookkeeping") {
    it("arms and reports per id") {
      val a = CommandWaiters.add(PATH_A, epoch())!!
      val b = CommandWaiters.add(PATH_B, epoch())!!

      CommandWaiters.markDeadlineArmed(a, epoch())

      CommandWaiters.isDeadlineArmed(a) shouldBe true
      CommandWaiters.isDeadlineArmed(b) shouldBe false
    }

    it("reports false for an id that was never armed") {
      CommandWaiters.isDeadlineArmed(4242L) shouldBe false
    }

    it("forgets the armed flag once the waiter is gone") {
      val id = CommandWaiters.add(PATH_A, epoch())!!
      CommandWaiters.markDeadlineArmed(id, epoch())
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND

      CommandWaiters.isDeadlineArmed(id) shouldBe false
    }
  }

  describe("clear") {
    it("reports what it removed, per path") {
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.add(PATH_B, epoch())

      CommandWaiters.clear(epoch()) shouldBe mapOf(PATH_A to 2, PATH_B to 1)
      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      CommandWaiters.consumeOldest(PATH_B, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("returns an empty map when there is nothing to remove") {
      CommandWaiters.clear(epoch()) shouldBe emptyMap()
    }

    it("removes only the waiters of the epoch it was given") {
      val stale = epoch()
      CommandWaiters.add(PATH_A, stale)
      DiagnosticGenerationRegistry.onServerStopped()
      CommandWaiters.add(PATH_B, epoch())

      CommandWaiters.clear(stale) shouldBe mapOf(PATH_A to 1)
      CommandWaiters.consumeOldest(PATH_B, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("does not reuse ids afterwards") {
      val before = CommandWaiters.add(PATH_A, epoch())!!
      CommandWaiters.clear(epoch())
      val after = CommandWaiters.add(PATH_A, epoch())!!

      // Reusing an id would let the cleared waiter's deadline cancel a brand new scan.
      (after > before) shouldBe true
    }

    it("drops the armed flags of what it removed") {
      val id = CommandWaiters.add(PATH_A, epoch())!!
      CommandWaiters.markDeadlineArmed(id, epoch())

      CommandWaiters.clear(epoch())

      CommandWaiters.isDeadlineArmed(id) shouldBe false
    }
  }
})
