package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/** 設計 §8.3 / §12 / A3。 */
class DiagnosticsReportTest : DescribeSpec({

  fun snapshot(
    featureStates: List<FeatureStateSnapshot> = emptyList(),
    tokenConfigured: Boolean = true,
    gitlabInstanceVersion: String? = null,
  ) = DiagnosticsSnapshot(
    ideVersion = "4.36.0",
    pluginVersion = "0.8.2",
    languageServerVersion = "9.3.0",
    gitlabInstanceVersion = gitlabInstanceVersion,
    instanceUrl = "https://gitlab.com",
    authenticationType = "token",
    tokenConfigured = tokenConfigured,
    languageServerLogLevel = "info",
    debugLogging = false,
    telemetryEnabled = true,
    codeSuggestionsEnabled = true,
    duoChatEnabled = true,
    securityScanEnabled = false,
    ignoreCertificateErrors = false,
    caCertificateConfigured = false,
    clientCertificateConfigured = false,
    languageServerRunning = true,
    languageServerLogPath = "/state/language_server.log",
    featureStates = featureStates,
  )

  describe("全体の形") {
    it("参照実装と同じ見出しで始まる") {
      DiagnosticsReport.render(snapshot()) shouldStartWith "# GitLab for Eclipse Diagnostics"
    }

    it("Versions / Configuration / Language Server の節を持つ") {
      val report = DiagnosticsReport.render(snapshot())
      report shouldContain "## Versions"
      report shouldContain "## Configuration"
      report shouldContain "## Language Server"
    }
  }

  describe("Versions") {
    it("4 つのバージョンを載せる") {
      val report = DiagnosticsReport.render(snapshot(gitlabInstanceVersion = "17.4.1"))
      report shouldContain "- IDE: Eclipse 4.36.0"
      report shouldContain "- Plugin: GitLab for Eclipse (0.8.2)"
      report shouldContain "- Language Server version: 9.3.0"
      report shouldContain "- GitLab instance version: 17.4.1"
    }

    it("未取得のインスタンスバージョンは Not available と書く") {
      DiagnosticsReport.render(snapshot()) shouldContain "- GitLab instance version: Not available"
    }
  }

  describe("Configuration") {
    it("トークンの有無だけを載せ、値は載せない(A3)") {
      val report = DiagnosticsReport.render(snapshot(tokenConfigured = true))
      report shouldContain "- Token configured: yes"
      report shouldNotContain "glpat"
    }

    it("未設定なら no と書く") {
      DiagnosticsReport.render(snapshot(tokenConfigured = false)) shouldContain "- Token configured: no"
    }
  }

  fun check(id: String, engaged: Boolean, details: String? = null) =
    FeatureStateChangeCheck(checkId = id, engaged = engaged, details = details)

  describe("feature state の節") {
    it("engaged が無ければ (On) で、各行は [x] ... (true)") {
      val report = DiagnosticsReport.render(
        snapshot(
          featureStates = listOf(
            FeatureStateSnapshot("GitLab Duo Chat", listOf(check("chat-no-license", engaged = false)))
          )
        )
      )
      report shouldContain "## GitLab Duo Chat (On)"
      report shouldContain "- [x] Valid GitLab license (true)"
    }

    it("engaged が 1 つでもあれば (Off) で、その行は [ ] ... (false)") {
      val report = DiagnosticsReport.render(
        snapshot(
          featureStates = listOf(
            FeatureStateSnapshot(
              "GitLab Duo Chat",
              listOf(check("chat-no-license", engaged = true), check("invalid-token", engaged = false)),
            )
          )
        )
      )
      report shouldContain "## GitLab Duo Chat (Off)"
      report shouldContain "- [ ] Valid GitLab license (false)"
      report shouldContain "- [x] Token is valid (true)"
    }

    it("engaged かつ details があれば引用行を足す") {
      val report = DiagnosticsReport.render(
        snapshot(
          featureStates = listOf(
            FeatureStateSnapshot(
              "GitLab Duo Chat",
              listOf(check("chat-no-license", engaged = true, details = "Seat not assigned")),
            )
          )
        )
      )
      report shouldContain "  > Seat not assigned"
    }

    it("engaged でなければ details は出さない") {
      val report = DiagnosticsReport.render(
        snapshot(
          featureStates = listOf(
            FeatureStateSnapshot(
              "GitLab Duo Chat",
              listOf(check("chat-no-license", engaged = false, details = "irrelevant")),
            )
          )
        )
      )
      report shouldNotContain "irrelevant"
    }

    it("既知でない checkId は checkId のまま出す") {
      val report = DiagnosticsReport.render(
        snapshot(
          featureStates = listOf(
            FeatureStateSnapshot("Future Feature", listOf(check("brand-new-check", engaged = false)))
          )
        )
      )
      report shouldContain "- [x] brand-new-check (true)"
    }

    it("参照実装と同じく unsupported-language の行は落とす") {
      val report = DiagnosticsReport.render(
        snapshot(
          featureStates = listOf(
            FeatureStateSnapshot(
              "GitLab Duo Code Suggestions",
              listOf(
                check(FeatureStateLabels.EXCLUDED_CHECK_ID, engaged = true),
                check("code-suggestions-no-license", engaged = false),
              ),
            )
          )
        )
      )
      report shouldNotContain "supported for the current file's language"
      // 落とした行は On/Off の判定にも影響しない
      report shouldContain "## GitLab Duo Code Suggestions (On)"
    }

    it("チェックが空の feature は節ごと出さない") {
      val report = DiagnosticsReport.render(
        snapshot(featureStates = listOf(FeatureStateSnapshot("Empty Feature", emptyList())))
      )
      report shouldNotContain "Empty Feature"
    }
  }

  describe("停止中の feature state(レビュー LOW-5)") {
    fun withChat(running: Boolean) = DiagnosticsReport.render(
      snapshot(
        featureStates = listOf(
          FeatureStateSnapshot("GitLab Duo Chat", listOf(check("chat-no-license", engaged = false)))
        )
      ).copy(languageServerRunning = running)
    )

    it("サーバ停止中は feature state が古い可能性を明示する") {
      withChat(running = false) shouldContain "may be out of date"
    }

    it("稼働中は注記を出さない") {
      withChat(running = true) shouldNotContain "may be out of date"
    }

    it("feature state が無ければ停止中でも注記を出さない") {
      val report = DiagnosticsReport.render(snapshot().copy(languageServerRunning = false))
      report shouldNotContain "may be out of date"
    }
  }

  describe("Language Server") {
    it("稼働状態とログファイルの場所を載せる") {
      val report = DiagnosticsReport.render(snapshot())
      report shouldContain "- Status: running"
      report shouldContain "- Log file: /state/language_server.log"
    }
  }

  describe("空のスナップショット") {
    it("feature state が無くても壊れない") {
      val report = DiagnosticsReport.render(snapshot())
      report.isNotBlank() shouldBe true
    }
  }
})
