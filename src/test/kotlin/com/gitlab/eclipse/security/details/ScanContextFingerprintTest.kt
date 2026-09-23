package com.gitlab.eclipse.security.details

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams.FeatureFlags
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams.SecurityScannerOptions
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.eclipse.lsp4j.WorkspaceFolder

/**
 * 設計 §11 / §15 / U7 / A13。
 *
 * 成分はちょうど 4 つ(`baseUrl` / `token` / `featureFlags.remoteSecurityScans` /
 * `securityScannerOptions.enabled`)。それ以外の設定が変わっても値が変わらないことが、
 * A13「`workspaceFolders` だけの部分送信では消えない」の土台になる。
 */
class ScanContextFingerprintTest : DescribeSpec({

  val base = GitLabLanguageServerConfigurationParams(
    baseUrl = "https://gitlab.example.com",
    token = "glpat-SECRET-TOKEN-123",
    featureFlags = FeatureFlags(remoteSecurityScans = true, streamCodeGenerations = false),
    securityScannerOptions = SecurityScannerOptions(enabled = true),
    logLevel = "info",
  )
  val fp = ScanContextFingerprint.of(base)

  describe("形") {
    it("SHA-256 の 16 進(64 文字)") {
      fp shouldMatch Regex("[0-9a-f]{64}")
    }

    it("同じ入力なら同じ値(決定的)") {
      ScanContextFingerprint.of(base.copy()) shouldBe fp
    }

    it("token を平文で含まない") {
      fp shouldNotContain "glpat"
      fp shouldNotContain "SECRET"
    }
  }

  describe("4 成分のそれぞれが値を変える") {
    it("baseUrl") {
      ScanContextFingerprint.of(base.copy(baseUrl = "https://other.example.com")) shouldNotBe fp
    }

    it("token") {
      ScanContextFingerprint.of(base.copy(token = "glpat-OTHER")) shouldNotBe fp
    }

    it("featureFlags.remoteSecurityScans") {
      ScanContextFingerprint.of(
        base.copy(featureFlags = FeatureFlags(remoteSecurityScans = false, streamCodeGenerations = false))
      ) shouldNotBe fp
    }

    it("securityScannerOptions.enabled") {
      ScanContextFingerprint.of(base.copy(securityScannerOptions = SecurityScannerOptions(enabled = false))) shouldNotBe
        fp
    }
  }

  describe("成分以外は値を変えない(A13 の土台)") {
    it("logLevel / workspaceFolders / telemetry / 他のフラグ等") {
      val noisy = base.copy(
        logLevel = "debug",
        workspaceFolders = listOf(WorkspaceFolder("file:///w", "w")),
        telemetry = GitLabLanguageServerConfigurationParams.Telemetry(enabled = true, trackingUrl = "https://t"),
        featureFlags = FeatureFlags(remoteSecurityScans = true, streamCodeGenerations = true),
        ignoreCertificateErrors = true,
        duoChat = GitLabLanguageServerConfigurationParams.DuoChat(enabled = true),
        codeCompletion = GitLabLanguageServerConfigurationParams.CodeCompletion(enabled = false),
      )
      ScanContextFingerprint.of(noisy) shouldBe fp
    }
  }

  describe("符号化が曖昧でない") {
    it("null と空文字を区別する") {
      ScanContextFingerprint.of(base.copy(token = null)) shouldNotBe ScanContextFingerprint.of(base.copy(token = ""))
      ScanContextFingerprint.of(base.copy(baseUrl = null)) shouldNotBe
        ScanContextFingerprint.of(base.copy(baseUrl = ""))
    }

    it("null の入れ子と false を区別する") {
      ScanContextFingerprint.of(base.copy(featureFlags = null)) shouldNotBe
        ScanContextFingerprint.of(
          base.copy(featureFlags = FeatureFlags(remoteSecurityScans = false, streamCodeGenerations = false))
        )
      ScanContextFingerprint.of(base.copy(securityScannerOptions = null)) shouldNotBe
        ScanContextFingerprint.of(base.copy(securityScannerOptions = SecurityScannerOptions(enabled = false)))
    }

    it("成分の境界をずらしても衝突しない") {
      val a = base.copy(baseUrl = "https://a", token = "bc")
      val b = base.copy(baseUrl = "https://ab", token = "c")
      ScanContextFingerprint.of(a) shouldNotBe ScanContextFingerprint.of(b)
    }

    it("区切りに使いそうな文字を値に含めても衝突しない") {
      val a = base.copy(baseUrl = "x", token = "1:y")
      val b = base.copy(baseUrl = "x1:", token = "y")
      ScanContextFingerprint.of(a) shouldNotBe ScanContextFingerprint.of(b)
    }
  }
})
