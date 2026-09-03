package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory

/**
 * Real directories, not mocks: the whole value of this class is what it leaves on disk and what it
 * deletes, so a mocked filesystem would only assert that the test author guessed right.
 */
class PatchQuarantineTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun newRoot(): File = createTempDirectory("quarantine").toFile()

  fun openOk(root: File, now: Instant = Instant.EPOCH, bytes: Long = 0) =
    PatchQuarantine(root) { now }.open(bytes) as PatchQuarantine.Opened.Ok

  describe("open") {
    it("stores the pre-image and restores it byte for byte") {
      val root = newRoot()
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.kt").writeText("before\n")
      val opened = openOk(root, bytes = 10)

      opened.session.saveContent("A.kt", "before\n".toByteArray(), executable = false, symlink = false)
      File(workTree, "A.kt").writeText("after\n")

      opened.session.restore("A.kt", workTree) shouldBe true
      File(workTree, "A.kt").readText() shouldBe "before\n"
    }

    it("restores the executable bit along with the content") {
      val root = newRoot()
      val workTree = createTempDirectory("wt").toFile()
      val opened = openOk(root)

      opened.session.saveContent("A.sh", "#!/bin/sh\n".toByteArray(), executable = true, symlink = false)
      File(workTree, "A.sh").writeText("replaced\n")

      opened.session.restore("A.sh", workTree) shouldBe true
      File(workTree, "A.sh").canExecute() shouldBe true
    }

    it("restores an absent path by deleting the file") {
      val root = newRoot()
      val workTree = createTempDirectory("wt").toFile()
      val opened = openOk(root)

      opened.session.saveAbsent("New.kt")
      File(workTree, "New.kt").writeText("added\n")

      opened.session.restore("New.kt", workTree) shouldBe true
      File(workTree, "New.kt").exists() shouldBe false
    }

    it("reports a path it never saved as not restorable") {
      openOk(newRoot()).session.restore("Unknown.kt", createTempDirectory("wt").toFile()) shouldBe false
    }

    it("prunes expired sessions to make room and reports how many it deleted") {
      val root = newRoot()
      val now = Instant.parse("2026-01-31T00:00:00Z")
      val old = now.minus(Duration.ofDays(30))
      repeat(PatchQuarantine.MAX_ENTRIES) { index ->
        File(root, "${old.toEpochMilli()}-$index").mkdirs()
      }

      openOk(root, now, bytes = 10).prunedCount shouldBe 1
    }

    it("refuses to open when only sessions inside the retention window exist") {
      val root = newRoot()
      val now = Instant.parse("2026-01-31T00:00:00Z")
      val fresh = now.minus(Duration.ofDays(1))
      repeat(PatchQuarantine.MAX_ENTRIES) { index ->
        File(root, "${fresh.toEpochMilli()}-$index").mkdirs()
      }

      PatchQuarantine(root) { now }.open(10) shouldBe PatchQuarantine.Opened.OutOfSpace
    }

    it("refuses a single apply larger than the total cap") {
      PatchQuarantine(newRoot()) { Instant.EPOCH }.open(PatchQuarantine.MAX_TOTAL_BYTES + 1) shouldBe
        PatchQuarantine.Opened.OutOfSpace
    }

    it("does not collide when two sessions open in the same millisecond") {
      val root = newRoot()
      val quarantine = PatchQuarantine(root) { Instant.EPOCH }
      val first = quarantine.open(1) as PatchQuarantine.Opened.Ok
      val second = quarantine.open(1) as PatchQuarantine.Opened.Ok

      first.session.id shouldNotBe second.session.id
    }
  }

  describe("complete") {
    it("marks the session so a crashed apply stays distinguishable") {
      val root = newRoot()
      val opened = openOk(root)

      opened.session.complete()

      File(root, opened.session.id).resolve("COMPLETE").exists() shouldBe true
    }
  }

  describe("sweep") {
    it("deletes only sessions past the retention window") {
      val root = newRoot()
      val now = Instant.parse("2026-01-31T00:00:00Z")
      File(root, "${now.minus(Duration.ofDays(30)).toEpochMilli()}-0").mkdirs()
      val kept = File(root, "${now.minus(Duration.ofDays(1)).toEpochMilli()}-1").apply { mkdirs() }

      PatchQuarantine(root) { now }.sweep() shouldBe 1

      kept.exists() shouldBe true
      root.listFiles()?.size shouldBe 1
    }

    it("keeps an incomplete session until it expires, so a crash stays recoverable") {
      val root = newRoot()
      val now = Instant.parse("2026-01-31T00:00:00Z")
      val crashed = File(root, "${now.minus(Duration.ofDays(1)).toEpochMilli()}-0").apply { mkdirs() }

      PatchQuarantine(root) { now }.sweep() shouldBe 0

      crashed.exists() shouldBe true
    }

    it("is a no-op when the area was never created") {
      PatchQuarantine(File(newRoot(), "never-made")) { Instant.EPOCH }.sweep() shouldBe 0
    }
  }
})
