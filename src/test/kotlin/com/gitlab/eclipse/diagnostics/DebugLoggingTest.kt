package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.utils.DebugLogging
import com.gitlab.eclipse.utils.debug
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.osgi.framework.Bundle

/**
 * 設計 §5.1.1 / A13。
 *
 * **この spec の主眼は「off のとき `ILog` を一切呼ばない」こと。** 出力の抑止ではなく配送の不在が
 * `gitlab.debug` の本質であり(VSCode の `log.ts:63` と同じ)、そこが検証できなければ F5 は成立しない。
 */
class DebugLoggingTest : DescribeSpec({

  fun log(): ILog = mockk<ILog>(relaxed = true).also {
    every { it.bundle } returns mockk<Bundle>(relaxed = true).also { b ->
      every { b.symbolicName } returns "com.gitlab.eclipse"
    }
  }

  afterEach { DebugLogging.isEnabled = { false } }

  describe("設定 off") {
    it("ILog を一度も呼ばない(A13)") {
      DebugLogging.isEnabled = { false }
      val target = log()
      target.debug("something happened")
      verify(exactly = 0) { target.log(any()) }
    }
  }

  describe("設定 on") {
    it("同じメッセージが ILog へ配送される(A13)") {
      DebugLogging.isEnabled = { true }
      val target = log()
      target.debug("something happened")
      verify(exactly = 1) { target.log(any()) }
    }

    it("INFO 重大度で [debug] 接頭辞を付ける") {
      DebugLogging.isEnabled = { true }
      val target = log()
      val captured = slot<IStatus>()
      every { target.log(capture(captured)) } returns Unit

      target.debug("something happened")

      captured.captured.severity shouldBe IStatus.INFO
      captured.captured.message shouldStartWith "[debug] "
      captured.captured.message shouldBe "[debug] something happened"
    }
  }

  describe("ゲートの評価タイミング") {
    it("呼び出しごとに評価するので、設定変更が次の 1 行から効く") {
      var enabled = false
      DebugLogging.isEnabled = { enabled }
      val target = log()

      target.debug("first")
      verify(exactly = 0) { target.log(any()) }

      enabled = true
      target.debug("second")
      verify(exactly = 1) { target.log(any()) }
    }
  }

  describe("遅延評価") {
    it("off のときメッセージを組み立てない") {
      DebugLogging.isEnabled = { false }
      var built = 0
      log().debug {
        built++
        "expensive"
      }
      built shouldBe 0
    }

    it("on のときだけ組み立てる") {
      DebugLogging.isEnabled = { true }
      var built = 0
      log().debug {
        built++
        "expensive"
      }
      built shouldBe 1
    }
  }

  describe("既定") {
    it("何も設定されていなければ無効(A8 と同じ既定)") {
      // afterEach が戻した状態がそのまま既定であること
      DebugLogging.isEnabled() shouldBe false
    }
  }
})
