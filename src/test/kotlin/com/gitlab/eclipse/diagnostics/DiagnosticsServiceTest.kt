package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/** 設計 §9.2〜§9.4 / §10.1 / A3 / A4 / A6 / A14。 */
class DiagnosticsServiceTest : DescribeSpec({

  lateinit var stateDir: Path

  beforeEach { stateDir = Files.createTempDirectory("diag-service-test") }
  afterEach { stateDir.toFile().deleteRecursively() }

  val token = "glpat-REALSECRET123"
  val opaqueToken = "zz9PlurAlphaTau"

  val collector = DiagnosticsSnapshotCollector(
    preferences = { error("no preference store in a headless test") },
    languageServerRunning = { true },
    languageServerVersion = { "9.3.0" },
    featureStates = {
      listOf(
        FeatureStateSnapshot(
          "GitLab Duo Chat",
          listOf(FeatureStateChangeCheck("chat-no-license", engaged = false)),
        )
      )
    },
    ideVersion = { "4.36.0" },
    pluginVersion = { "0.8.2" },
    logDirectory = { stateDir },
  )

  fun service(
    buffer: LogRingBuffer = LogRingBuffer(),
    secrets: () -> Collection<String> = { listOf(token) },
    tokenConfigured: Boolean = true,
  ) = DiagnosticsService(
    collector = collector,
    logBuffer = buffer,
    stateDirectory = { stateDir },
    knownSecrets = secrets,
    // Headless: secure storage is unavailable. Returning a value here is also what decides the
    // report's "Token configured" line, which is the point of the single-read change.
    publishStoredSecrets = { if (tokenConfigured) listOf(token) else emptyList() },
  )

  fun entriesOf(bytes: ByteArray): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var e = zip.nextEntry
      while (e != null) {
        result[e.name] = zip.readBytes().toString(Charsets.UTF_8)
        e = zip.nextEntry
      }
    }
    return result
  }

  describe("収集が壊れても報告を返す(設計 §14)") {
    it("設定ストアが使えなくてもレポートを生成する") {
      val report = service().report()
      report shouldContain "# GitLab for Eclipse Diagnostics"
      report shouldContain "- Instance URL: ${DiagnosticsSnapshotCollector.NOT_AVAILABLE}"
    }

    it("トークンの有無だけを載せる(A3)") {
      service(tokenConfigured = true).report() shouldContain "- Token configured: yes"
      service(tokenConfigured = false).report() shouldContain "- Token configured: no"
    }
  }

  describe("拡張ログ") {
    it("何も記録されていなければ参照実装と同じ文言を返す") {
      service().extensionLogs() shouldBe DiagnosticsService.NO_EXTENSION_LOGS
    }

    it("記録された行を返す") {
      val buffer = LogRingBuffer().also { it.append("a line") }
      service(buffer).extensionLogs() shouldContain "a line"
    }
  }

  describe("言語サーバログ(A6)") {
    it("ファイルが無くてもエクスポートを失敗させない") {
      service().languageServerLogs() shouldBe DiagnosticsService.NO_LANGUAGE_SERVER_LOGS
    }

    it("あれば内容を返す") {
      Files.writeString(
        stateDir.resolve(DiagnosticsSnapshotCollector.LANGUAGE_SERVER_LOG),
        "server started",
      )
      service().languageServerLogs() shouldContain "server started"
    }

    it("空ファイルは不在と同じ扱いにする") {
      Files.writeString(stateDir.resolve(DiagnosticsSnapshotCollector.LANGUAGE_SERVER_LOG), "")
      service().languageServerLogs() shouldBe DiagnosticsService.NO_LANGUAGE_SERVER_LOGS
    }

    it("不正なバイト列を含んでも読み捨てない(レビュー MEDIUM-3)") {
      // log4j は charset 無指定で書き、LS の stderr はプラットフォーム既定で復号される。
      // readString なら MalformedInputException で「サーバが動いていない」に化けていた。
      val bytes = "before ".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
        " after".toByteArray()
      Files.write(stateDir.resolve(DiagnosticsSnapshotCollector.LANGUAGE_SERVER_LOG), bytes)
      val logs = service().languageServerLogs()
      logs shouldContain "before"
      logs shouldContain "after"
      logs shouldNotBe DiagnosticsService.NO_LANGUAGE_SERVER_LOGS
    }
  }

  describe("アーカイブ(A4)") {
    it("参照実装と同じ 3 エントリを持つ") {
      entriesOf(service().buildArchive()).keys.toList() shouldContainExactly listOf(
        DiagnosticsService.REPORT_ENTRY,
        DiagnosticsService.EXTENSION_LOG_ENTRY,
        DiagnosticsService.LANGUAGE_SERVER_LOG_ENTRY,
      )
    }
  }

  describe("★ A14: 3 エントリすべてで平文のトークンが出ない") {
    it("ILog 由来・LS ログ由来・レポート由来のいずれからも漏れない") {
      // ILog 由来(リングバッファ)
      val buffer = LogRingBuffer().also {
        it.append("request failed with PRIVATE-TOKEN: $token")
        it.append("""payload {"access_token":"$opaqueToken"}""")
      }
      // LS ログ由来
      Files.writeString(
        stateDir.resolve(DiagnosticsSnapshotCollector.LANGUAGE_SERVER_LOG),
        "GET /api/v4/user?private_token=$token\nauth used $opaqueToken\n",
      )

      val entries = entriesOf(service(buffer, secrets = { listOf(token, opaqueToken) }).buildArchive())

      entries.size shouldBe 3
      entries.forEach { (_, content) ->
        content shouldNotContain token
        content shouldNotContain opaqueToken
      }
    }

    it("接頭辞を持たないトークンは実値置換でしか捕まらない(規則の実効性の確認)") {
      val buffer = LogRingBuffer().also { it.append("bare $opaqueToken in prose") }
      // 実値を渡さない場合、形に依存するパターンではこのトークンに当たらない
      val leaked = entriesOf(service(buffer, secrets = { emptyList() }).buildArchive())
      leaked[DiagnosticsService.EXTENSION_LOG_ENTRY] shouldContain opaqueToken
      // 実値を渡せば伏せられる
      val redacted = entriesOf(service(buffer, secrets = { listOf(opaqueToken) }).buildArchive())
      redacted[DiagnosticsService.EXTENSION_LOG_ENTRY] shouldNotContain opaqueToken
    }

    it("トークン取得が例外を投げてもレポート生成は続行する") {
      val buffer = LogRingBuffer().also { it.append("plain line") }
      val entries = entriesOf(service(buffer, secrets = { error("koin down") }).buildArchive())
      entries.size shouldBe 3
      entries[DiagnosticsService.EXTENSION_LOG_ENTRY] shouldContain "plain line"
    }
  }

  describe("secure storage は 1 コマンド 1 回だけ読む(レビュー Low-1)") {
    it("レポート 1 回につき publish は 1 回") {
      var reads = 0
      DiagnosticsService(
        collector = collector,
        logBuffer = LogRingBuffer(),
        stateDirectory = { stateDir },
        knownSecrets = { emptyList() },
        publishStoredSecrets = {
          reads++
          listOf(token)
        },
      ).report()
      reads shouldBe 1
    }

    it("エクスポート 1 回につき publish は 1 回(3 エントリ分読まない)") {
      var reads = 0
      DiagnosticsService(
        collector = collector,
        logBuffer = LogRingBuffer(),
        stateDirectory = { stateDir },
        knownSecrets = { emptyList() },
        publishStoredSecrets = {
          reads++
          listOf(token)
        },
      ).buildArchive()
      reads shouldBe 1
    }

    it("その 1 回の結果が Token configured を決める") {
      fun reportWith(stored: List<String>) = DiagnosticsService(
        collector = collector,
        logBuffer = LogRingBuffer(),
        stateDirectory = { stateDir },
        knownSecrets = { emptyList() },
        publishStoredSecrets = { stored },
      ).report()

      reportWith(listOf(token)) shouldContain "- Token configured: yes"
      reportWith(emptyList()) shouldContain "- Token configured: no"
    }
  }

  describe("materialize") {
    it("状態ディレクトリに書き、そのパスを返す") {
      val path = materializeDiagnosticsFile("out.txt", "content", stateDir)
      path.parent shouldBe stateDir
      Files.readString(path) shouldBe "content"
    }
  }
})
