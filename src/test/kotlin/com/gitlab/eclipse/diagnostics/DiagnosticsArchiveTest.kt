package com.gitlab.eclipse.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 設計 §8.1 / §9.4.1 / A4 / A15 / A16。 */
class DiagnosticsArchiveTest : DescribeSpec({

  fun readZip(bytes: ByteArray): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
        entry = zip.nextEntry
      }
    }
    return result
  }

  val entries = listOf(
    DiagnosticsEntry("diagnostics.md", "# report"),
    DiagnosticsEntry("extension-logs.txt", "log line"),
    DiagnosticsEntry("language-server-logs.txt", "ls line"),
  )

  describe("build(A4)") {
    it("参照実装と同じ 3 エントリをその名前で持つ") {
      val zip = readZip(DiagnosticsArchive.build(entries))
      zip.keys.toList() shouldContainExactly
        listOf("diagnostics.md", "extension-logs.txt", "language-server-logs.txt")
    }

    it("各エントリの内容を保つ") {
      val zip = readZip(DiagnosticsArchive.build(entries))
      zip["diagnostics.md"] shouldBe "# report"
      zip["language-server-logs.txt"] shouldBe "ls line"
    }

    it("非 ASCII を UTF-8 で往復できる") {
      val zip = readZip(DiagnosticsArchive.build(listOf(DiagnosticsEntry("a.txt", "日本語テスト"))))
      zip["a.txt"] shouldBe "日本語テスト"
    }

    it("空の内容でも壊れない") {
      val zip = readZip(DiagnosticsArchive.build(listOf(DiagnosticsEntry("empty.txt", ""))))
      zip["empty.txt"] shouldBe ""
    }
  }

  describe("writeAtomically(§9.4.1)") {
    lateinit var dir: Path

    beforeEach { dir = Files.createTempDirectory("diag-archive-test") }
    afterEach { dir.toFile().deleteRecursively() }

    it("目的のパスに完全な ZIP を書く") {
      val target = dir.resolve("out.zip")
      DiagnosticsArchive.writeAtomically(DiagnosticsArchive.build(entries), target)
      readZip(Files.readAllBytes(target)).keys.size shouldBe 3
    }

    it("既存ファイルを置き換える") {
      val target = dir.resolve("out.zip")
      Files.write(target, "stale".toByteArray())
      DiagnosticsArchive.writeAtomically(DiagnosticsArchive.build(entries), target)
      readZip(Files.readAllBytes(target)).keys.size shouldBe 3
    }

    it("成功後に一時ファイルを残さない(A16)") {
      val target = dir.resolve("out.zip")
      DiagnosticsArchive.writeAtomically(DiagnosticsArchive.build(entries), target)
      Files.list(dir).use { it.toList().map { p -> p.fileName.toString() } } shouldContainExactly
        listOf("out.zip")
    }

    it("失敗しても一時ファイルも部分ファイルも残さない(A16)") {
      val target = dir.resolve("missing-subdir").resolve("out.zip")
      runCatching { DiagnosticsArchive.writeAtomically(ByteArray(16), target) }.isFailure shouldBe true
      Files.exists(target) shouldBe false
      // 親ディレクトリ自体が無いので、この dir 直下に残骸が無いことを確認する
      Files.list(dir).use { it.toList() }.isEmpty() shouldBe true
    }

    it("同一パスへの同時書き込みの後も完全に読める ZIP が残る(A15)") {
      val target = dir.resolve("race.zip")
      val bytes = DiagnosticsArchive.build(entries)
      val pool = Executors.newFixedThreadPool(2)
      val done = CountDownLatch(2)
      try {
        repeat(2) {
          pool.submit {
            runCatching { DiagnosticsArchive.writeAtomically(bytes, target) }
            done.countDown()
          }
        }
        done.await(30, TimeUnit.SECONDS) shouldBe true
      } finally {
        pool.shutdownNow()
      }
      readZip(Files.readAllBytes(target)).keys.size shouldBe 3
      // 一時ファイルが取り残されていないこと
      Files.list(dir).use { it.toList() }.size shouldBe 1
    }
  }

  describe("ファイル名(参照実装と同形)") {
    it("gitlab-diagnostics-<timestamp>.zip の形にする") {
      val name = DiagnosticsFileNaming.archiveName(
        Instant.parse("2026-09-21T10:11:12.345Z"),
        ZoneOffset.UTC,
      )
      name shouldBe "gitlab-diagnostics-2026-09-21T10-11-12.zip"
    }

    it("時刻が変われば名前も変わる") {
      val a = DiagnosticsFileNaming.archiveName(Instant.parse("2026-09-21T10:11:12Z"), ZoneOffset.UTC)
      val b = DiagnosticsFileNaming.archiveName(Instant.parse("2026-09-21T10:11:13Z"), ZoneOffset.UTC)
      a shouldNotBe b
    }
  }
})
