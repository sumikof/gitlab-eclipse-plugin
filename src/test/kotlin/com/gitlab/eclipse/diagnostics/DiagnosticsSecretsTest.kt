package com.gitlab.eclipse.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * 設計 §10.1。
 *
 * **このキャッシュが存在する理由そのものを固定する spec。** ログ 1 行ごとにトークンを引くと、
 * OAuth 経路の `getToken()` が `refreshTokenIfExpired()` を通ってネットワーク往復・ログ出力・
 * 通知表示を行う(`OAuthTokenProvider.kt:69-87`)。それを Eclipse のログ配送の内側でやると、
 * ログがネットワークでブロックし、自分のログで再入し、ログ呼び出しからダイアログが出る。
 * したがって **読み取り経路は絶対に I/O を含んではならない**。
 */
class DiagnosticsSecretsTest : DescribeSpec({

  beforeEach { DiagnosticsSecrets.clear() }
  afterEach { DiagnosticsSecrets.clear() }

  describe("publish") {
    it("公開した値を返す") {
      DiagnosticsSecrets.publish("tok-aaaa1111")
      DiagnosticsSecrets.current() shouldContainAll listOf("tok-aaaa1111")
    }

    it("空・ブランク・null は無視する") {
      DiagnosticsSecrets.publish(null, "", "   ")
      DiagnosticsSecrets.current().shouldBeEmpty()
    }

    it("ローテーション後も旧い値を保持する(Codex round 2 P1 の漏洩経路)") {
      DiagnosticsSecrets.publish("tok-OLD-1111")
      DiagnosticsSecrets.publish("tok-NEW-2222")
      // 旧トークンを落とすと、それを含む既存のログ行が伏せられなくなる
      DiagnosticsSecrets.current() shouldContainAll listOf("tok-OLD-1111", "tok-NEW-2222")
    }

    it("同じ値を二度公開しても増えない") {
      DiagnosticsSecrets.publish("tok-aaaa1111")
      DiagnosticsSecrets.publish("tok-aaaa1111")
      DiagnosticsSecrets.current().size shouldBe 1
    }
  }

  describe("ログ経路との結線") {
    it("タップはキャッシュだけを読み、トークンプロバイダには触れない") {
      DiagnosticsSecrets.publish("tok-live-9999")
      val buffer = LogRingBuffer()
      // 本番の既定と同じ経路(configuredSecrets → DiagnosticsSecrets.current)を通す
      val tap = PluginLogTap(buffer, knownSecrets = ::configuredSecrets)
      tap.logging(
        org.eclipse.core.runtime.Status(
          org.eclipse.core.runtime.IStatus.INFO,
          "p",
          "auth header carried tok-live-9999",
        ),
        "p",
      )
      buffer.getAll() shouldNotContain "tok-live-9999"
    }

    it("何も公開されていなくてもログ収集は動く") {
      val buffer = LogRingBuffer()
      val tap = PluginLogTap(buffer, knownSecrets = ::configuredSecrets)
      tap.logging(
        org.eclipse.core.runtime.Status(
          org.eclipse.core.runtime.IStatus.INFO,
          "p",
          "nothing secret here",
        ),
        "p",
      )
      buffer.lineCount() shouldBe 1
    }
  }
})
