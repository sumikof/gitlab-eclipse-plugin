package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.chat.quickchat.ChunkAssembler.Change
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ChunkAssemblerTest : DescribeSpec({
  fun chunk(id: Int?, text: String?, req: String? = "r1", role: String? = "ASSISTANT", errors: List<String>? = null) =
    StreamFrame(req, role, text, errors, id)
  fun confirmed(vararg limits: Int): ChunkAssembler {
    val a = if (limits.isEmpty()) {
      ChunkAssembler()
    } else {
      ChunkAssembler(
        limits[0],
        limits.getOrElse(1) { 4096 },
        limits.getOrElse(2) { 256 },
      )
    }
    a.confirmRequestId("r1")
    return a
  }

  describe("order") {
    it("shows chunks in order as they arrive") {
      val a = confirmed()
      a.accept(chunk(1, "He")) shouldBe Change.DISPLAY
      a.accept(chunk(2, "llo")) shouldBe Change.DISPLAY
      a.displayText() shouldBe "Hello"
    }
    it("holds chunks after a gap until the gap is filled") {
      val a = confirmed()
      a.accept(chunk(2, "B")) shouldBe Change.NONE
      a.accept(chunk(3, "C")) shouldBe Change.NONE
      a.displayText() shouldBe ""
      a.accept(chunk(1, "A")) shouldBe Change.DISPLAY
      a.displayText() shouldBe "ABC"
    }
    it("treats a null content as an empty chunk, not a gap") {
      val a = confirmed()
      a.accept(chunk(1, "A"))
      a.accept(chunk(2, null))
      a.accept(chunk(3, "C"))
      a.displayText() shouldBe "AC"
    }
    it("ignores chunkId below 1") {
      val a = confirmed()
      a.accept(chunk(0, "x")) shouldBe Change.NONE
      a.accept(chunk(-1, "x")) shouldBe Change.NONE
      a.displayText() shouldBe ""
    }
  }

  describe("series (Codex round 1 #3)") {
    it("restarts when chunk 1 arrives again") {
      val a = confirmed()
      a.accept(chunk(1, "old1"))
      a.accept(chunk(2, "old2"))
      a.accept(chunk(1, "new1"))
      a.accept(chunk(2, "new2"))
      a.displayText() shouldBe "new1new2"
      a.seriesResets shouldBe 1
    }
    it("restarts when the new series' 2 overtakes its 1") {
      val a = confirmed()
      a.accept(chunk(1, "old1"))
      a.accept(chunk(2, "old2"))
      a.accept(chunk(2, "new2"))
      a.displayText() shouldBe ""
      a.accept(chunk(1, "new1"))
      a.displayText() shouldBe "new1new2"
      a.seriesResets shouldBe 1
    }
  }

  describe("requestId") {
    it("keeps frames until the requestId is known, then replays only matching ones") {
      val a = ChunkAssembler()
      a.accept(chunk(1, "A")) shouldBe Change.NONE
      a.accept(chunk(1, "X", req = "other")) shouldBe Change.NONE
      a.accept(chunk(2, "B")) shouldBe Change.NONE
      a.confirmRequestId("r1") shouldBe Change.DISPLAY
      a.displayText() shouldBe "AB"
    }
    it("drops frames of another requestId or a non-assistant role once known") {
      val a = confirmed()
      a.accept(chunk(1, "X", req = "other")) shouldBe Change.NONE
      a.accept(chunk(1, "S", role = "SYSTEM")) shouldBe Change.NONE
      a.accept(chunk(1, "a", role = "assistant")) shouldBe Change.DISPLAY
      a.displayText() shouldBe "a"
    }
    it("ignores a second confirmRequestId") {
      val a = confirmed()
      a.confirmRequestId("r2") shouldBe Change.NONE
      a.accept(chunk(1, "A")) shouldBe Change.DISPLAY
    }
  }

  describe("final message") {
    it("replaces the display with the final content and ignores later frames") {
      val a = confirmed()
      a.accept(chunk(1, "draft"))
      a.accept(chunk(null, "Final")) shouldBe Change.DISPLAY
      a.displayText() shouldBe "Final"
      a.finalReceived shouldBe true
      a.accept(chunk(2, "late")) shouldBe Change.NONE
      a.displayText() shouldBe "Final"
    }
    it("does not change the display for a final with errors") {
      val a = confirmed()
      a.accept(chunk(1, "draft"))
      a.accept(chunk(null, "oops", errors = listOf("A1000"))) shouldBe Change.NONE
      a.displayText() shouldBe "draft"
      a.finalReceived shouldBe false
    }
  }

  describe("limits (Codex round 1 #2 / round 2 #1)") {
    it("keeps exactly the char limit and overflows one past it, before storing") {
      val a = confirmed(4)
      a.accept(chunk(1, "abcd")) shouldBe Change.DISPLAY
      a.accept(chunk(2, "e")) shouldBe Change.OVERFLOW
      a.displayText() shouldBe "abcd"
      a.accept(chunk(3, "")) shouldBe Change.NONE
    }
    it("counts chunks held after a gap") {
      val a = confirmed(4)
      a.accept(chunk(2, "ab")) shouldBe Change.NONE
      a.accept(chunk(3, "cde")) shouldBe Change.OVERFLOW
    }
    it("overflows on the count of chunks held after a gap, not on the contiguous prefix") {
      val a = confirmed(1000, 2)
      (1..5).forEach { a.accept(chunk(it, "x")) shouldBe Change.DISPLAY }
      a.accept(chunk(7, "a")) shouldBe Change.NONE
      a.accept(chunk(8, "b")) shouldBe Change.NONE
      a.accept(chunk(9, "c")) shouldBe Change.OVERFLOW
    }
    it("frees the held-chunk count when the gap fills") {
      val a = confirmed(1000, 2)
      a.accept(chunk(2, "b")) shouldBe Change.NONE
      a.accept(chunk(3, "c")) shouldBe Change.NONE
      a.accept(chunk(1, "a")) shouldBe Change.DISPLAY
      a.accept(chunk(5, "e")) shouldBe Change.NONE
      a.accept(chunk(6, "f")) shouldBe Change.NONE
      a.displayText() shouldBe "abc"
    }
    it("does not overflow on 5000 in-order chunks under the char cap") {
      val a = confirmed()
      (1..5000).forEach { a.accept(chunk(it, "ab")) shouldBe Change.DISPLAY }
      a.displayText().length shouldBe 10_000
      a.chunksAccepted shouldBe 5000
    }
    it("overflows on pending frames before the requestId is known") {
      val a = ChunkAssembler(1000, 4096, 2)
      a.accept(chunk(1, "a"))
      a.accept(chunk(2, "b"))
      a.accept(chunk(3, "c")) shouldBe Change.OVERFLOW
    }
    it("overflows on pending chars before the requestId is known") {
      val a = ChunkAssembler(3, 4096, 256)
      a.accept(chunk(1, "ab")) shouldBe Change.NONE
      a.accept(chunk(2, "cd", req = "other")) shouldBe Change.OVERFLOW
    }
    it("frees chars when a series restarts, so repeated restarts alone never overflow") {
      val a = confirmed(4)
      repeat(100) { a.accept(chunk(1, "abcd")) shouldBe Change.DISPLAY }
      a.seriesResets shouldBe 99
    }
  }

  describe("additional cases") {
    it("replays a pending final and drops pending frames after it") {
      val a = ChunkAssembler()
      a.accept(chunk(1, "draft"))
      a.accept(chunk(null, "Final"))
      a.accept(chunk(2, "late"))
      a.confirmRequestId("r1") shouldBe Change.DISPLAY
      a.displayText() shouldBe "Final"
      a.finalReceived shouldBe true
    }
    it("returns NONE when no pending frame matches the confirmed requestId") {
      val a = ChunkAssembler()
      a.accept(chunk(1, "X", req = "other"))
      a.confirmRequestId("r1") shouldBe Change.NONE
      a.displayText() shouldBe ""
    }
    it("reports OVERFLOW from the replay and ignores everything afterwards") {
      val a = ChunkAssembler(3, 4096, 256)
      a.accept(chunk(1, "ab"))
      a.accept(chunk(2, "c"))
      a.confirmRequestId("r1") shouldBe Change.DISPLAY
      a.accept(chunk(3, "d")) shouldBe Change.OVERFLOW
      a.accept(chunk(null, "Final")) shouldBe Change.NONE
      a.finalReceived shouldBe false
    }
    it("keeps accepting chunks after a final with errors") {
      val a = confirmed()
      a.accept(chunk(null, "oops", errors = listOf("A1000"))) shouldBe Change.NONE
      a.accept(chunk(1, "A")) shouldBe Change.DISPLAY
      a.displayText() shouldBe "A"
    }
    it("counts accepted chunks and frees chunk chars on the final") {
      val a = confirmed(4)
      a.accept(chunk(1, "ab"))
      a.accept(chunk(3, "cd"))
      a.chunksAccepted shouldBe 2
      a.accept(chunk(null, "wxyz")) shouldBe Change.DISPLAY
      a.displayText() shouldBe "wxyz"
    }
  }
})
