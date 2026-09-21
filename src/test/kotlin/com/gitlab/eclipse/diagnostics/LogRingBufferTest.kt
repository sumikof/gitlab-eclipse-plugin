package com.gitlab.eclipse.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 設計 §8.1 / A1。参照実装 `src/common/diagnostics/log_collector.ts` の移植。 */
class LogRingBufferTest : DescribeSpec({

  describe("満杯になる前") {
    it("空なら空文字を返す") {
      LogRingBuffer().getAll() shouldBe ""
      LogRingBuffer().lineCount() shouldBe 0
    }

    it("投入順に返す") {
      val buffer = LogRingBuffer()
      listOf("a", "b", "c").forEach(buffer::append)
      buffer.getAll() shouldBe "a\nb\nc"
      buffer.lineCount() shouldBe 3
    }
  }

  describe("満杯になった後") {
    it("最古を上書きし、行数は上限で頭打ちになる") {
      val buffer = LogRingBuffer(capacity = 3)
      listOf("a", "b", "c", "d").forEach(buffer::append)
      buffer.lineCount() shouldBe 3
      buffer.getAll() shouldBe "b\nc\nd"
    }

    it("何周しても時系列順を保つ") {
      val buffer = LogRingBuffer(capacity = 3)
      (1..10).forEach { buffer.append("line$it") }
      buffer.getAll() shouldBe "line8\nline9\nline10"
    }

    it("上限ちょうどでは何も落とさない") {
      val buffer = LogRingBuffer(capacity = 3)
      listOf("a", "b", "c").forEach(buffer::append)
      buffer.getAll() shouldBe "a\nb\nc"
    }
  }

  describe("clear") {
    it("空に戻し、その後の投入は先頭から積まれる") {
      val buffer = LogRingBuffer(capacity = 3)
      listOf("a", "b", "c", "d").forEach(buffer::append)
      buffer.clear()
      buffer.lineCount() shouldBe 0
      buffer.getAll() shouldBe ""
      buffer.append("x")
      buffer.getAll() shouldBe "x"
    }
  }

  describe("既定の容量") {
    it("参照実装と同じ 5000 行") {
      val buffer = LogRingBuffer()
      (1..5001).forEach { buffer.append("line$it") }
      buffer.lineCount() shouldBe 5000
      buffer.getAll() shouldNotContain "line1\n"
      buffer.getAll() shouldContain "line5001"
    }
  }

  describe("並行 append") {
    it("行を失わず、壊れた行も作らない") {
      val buffer = LogRingBuffer(capacity = 10_000)
      val threads = 8
      val perThread = 500
      val pool = Executors.newFixedThreadPool(threads)
      val ready = CountDownLatch(threads)
      try {
        repeat(threads) { t ->
          pool.submit {
            repeat(perThread) { i -> buffer.append("t$t-$i") }
            ready.countDown()
          }
        }
        ready.await(30, TimeUnit.SECONDS) shouldBe true
      } finally {
        pool.shutdownNow()
      }
      buffer.lineCount() shouldBe threads * perThread
      // 各スレッドの寄与が全部残っていること = ロストも重複破壊もない
      val lines = buffer.getAll().lines()
      lines.size shouldBe threads * perThread
      repeat(threads) { t -> lines.count { it.startsWith("t$t-") } shouldBe perThread }
    }
  }
})
