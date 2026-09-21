package com.gitlab.eclipse.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import java.time.Instant
import java.time.ZoneOffset

/** 設計 §9.1 / §13 / §14 / A7 / A17。 */
class PluginLogTapTest : DescribeSpec({

  val fixedClock = { Instant.parse("2026-09-21T10:11:12.345Z") }

  fun tap(buffer: LogRingBuffer) = PluginLogTap(buffer, fixedClock, ZoneOffset.UTC)

  describe("整形") {
    it("タイムスタンプと重大度と本文を 1 行にする") {
      val buffer = LogRingBuffer()
      tap(buffer).logging(Status(IStatus.INFO, "com.gitlab.eclipse", "hello"), "com.gitlab.eclipse")
      buffer.getAll() shouldBe "2026-09-21T10:11:12.345 [info]: hello"
    }

    it("重大度を参照実装と同じ語彙で書く") {
      mapOf(
        IStatus.OK to "info",
        IStatus.INFO to "info",
        IStatus.WARNING to "warning",
        IStatus.ERROR to "error",
        IStatus.CANCEL to "cancel",
      ).forEach { (severity, label) ->
        val buffer = LogRingBuffer()
        tap(buffer).logging(Status(severity, "p", "m"), "p")
        buffer.getAll() shouldContain "[$label]:"
      }
    }
  }

  describe("複数行(A17)") {
    it("スタックトレースの各物理行を別々に数える") {
      val buffer = LogRingBuffer()
      val boom = RuntimeException("boom")
      tap(buffer).logging(Status(IStatus.ERROR, "p", "failed", boom), "p")
      // 1 行目 + スタックトレースの各行。1 件で 1 行しか数えないなら 1 になる
      buffer.lineCount() shouldBeGreaterThan 3
      buffer.getAll() shouldContain "boom"
    }

    it("本文中の改行も物理行に割る") {
      val buffer = LogRingBuffer()
      tap(buffer).logging(Status(IStatus.INFO, "p", "line1\nline2\nline3"), "p")
      buffer.lineCount() shouldBe 3
    }

    it("CRLF と CR を正規化する") {
      val buffer = LogRingBuffer()
      tap(buffer).logging(Status(IStatus.INFO, "p", "a\r\nb\rc"), "p")
      buffer.lineCount() shouldBe 3
    }

    it("スタックトレース行を 4 スペース字下げする") {
      val buffer = LogRingBuffer()
      tap(buffer).logging(Status(IStatus.ERROR, "p", "failed", RuntimeException("boom")), "p")
      val lines = buffer.getAll().lines()
      lines[0] shouldStartWith "2026-09-21T10:11:12.345 [error]: failed"
      lines[1] shouldStartWith "    "
    }
  }

  describe("堅牢性") {
    it("バッファが投げても呼び出し元へ伝播させない(A7 / N3)") {
      val exploding = object : LogRingBuffer() {
        override fun append(line: String) = error("buffer is broken")
      }
      // 例外が出ないことが条件
      tap(exploding).logging(Status(IStatus.INFO, "p", "m"), "p")
    }

    it("本文が null でも壊れない") {
      val buffer = LogRingBuffer()
      tap(buffer).logging(Status(IStatus.INFO, "p", null, null), "p")
      buffer.lineCount() shouldBeGreaterThan 0
    }
  }

  describe("捕捉時点のサニタイズ(Codex round 2 P1)") {
    it("そのとき現行のトークンをログ行から伏せる") {
      val buffer = LogRingBuffer()
      val tap = PluginLogTap(buffer, fixedClock, ZoneOffset.UTC) { listOf("tok-CURRENT-1") }
      tap.logging(Status(IStatus.INFO, "p", "calling with tok-CURRENT-1"), "p")
      buffer.getAll() shouldNotContain "tok-CURRENT-1"
      buffer.getAll() shouldContain "[REDACTED_TOKEN]"
    }

    it("トークンが更新されても、更新前に捕捉した行は伏せられたまま") {
      val buffer = LogRingBuffer()
      var current = "tok-OLD-0001"
      val tap = PluginLogTap(buffer, fixedClock, ZoneOffset.UTC) { listOf(current) }

      tap.logging(Status(IStatus.INFO, "p", "used $current"), "p")
      current = "tok-NEW-0002"
      tap.logging(Status(IStatus.INFO, "p", "used $current"), "p")

      // エクスポート時にしか伏せないなら、旧トークンがここに平文で残る
      buffer.getAll() shouldNotContain "tok-OLD-0001"
      buffer.getAll() shouldNotContain "tok-NEW-0002"
    }

    it("スタックトレース行のトークンも伏せる") {
      val buffer = LogRingBuffer()
      val tap = PluginLogTap(buffer, fixedClock, ZoneOffset.UTC) { listOf("tok-SECRET-9") }
      tap.logging(
        Status(IStatus.ERROR, "p", "failed", RuntimeException("rejected tok-SECRET-9")),
        "p",
      )
      buffer.getAll() shouldNotContain "tok-SECRET-9"
    }

    it("トークン取得が例外を投げてもログ行を失わない") {
      val buffer = LogRingBuffer()
      val tap = PluginLogTap(buffer, fixedClock, ZoneOffset.UTC) { error("koin is down") }
      tap.logging(Status(IStatus.INFO, "p", "still logged"), "p")
      buffer.getAll() shouldContain "still logged"
    }
  }

  describe("再入(設計 §13)") {
    it("append の中から再び logging されても無限再帰しない") {
      val buffer = LogRingBuffer()
      lateinit var tapRef: PluginLogTap
      val reentrant = object : LogRingBuffer() {
        var depth = 0
        override fun append(line: String) {
          depth++
          if (depth < 3) tapRef.logging(Status(IStatus.INFO, "p", "nested"), "p")
          super.append(line)
        }
      }
      tapRef = tap(reentrant)
      tapRef.logging(Status(IStatus.INFO, "p", "outer"), "p")
      // 再入分は捨てられ、外側の 1 行だけが残る
      reentrant.getAll() shouldBe "2026-09-21T10:11:12.345 [info]: outer"
    }
  }
})
