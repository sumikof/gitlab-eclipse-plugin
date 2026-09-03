package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.lib.FileMode
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory

/**
 * Real files, not mocks: every guarantee this class makes is about what survives on disk when a
 * write fails halfway, which only real files can show.
 */
class WorkTreePatchWriterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun newSession(): PatchQuarantine.Session {
    val root = createTempDirectory("quarantine").toFile()
    return (PatchQuarantine(root) { Instant.EPOCH }.open(0) as PatchQuarantine.Opened.Ok).session
  }

  fun modify(path: String, text: String, mode: FileMode = FileMode.REGULAR_FILE) =
    PatchChange(path, PatchChangeKind.MODIFY, text.toByteArray(), mode)

  /**
   * A change whose parent is an existing regular file: the write cannot succeed, which is how
   * these tests force the rollback path without stubbing the filesystem.
   */
  fun unwritable() = modify("blocked/inner.txt", "c\n")

  fun workTreeWithBlocker(): File {
    val workTree = createTempDirectory("wt").toFile()
    File(workTree, "blocked").writeText("x")
    return workTree
  }

  describe("write") {
    it("replaces the file content and reports the path") {
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.txt").writeText("a\n")

      val result = WorkTreePatchWriter().write(workTree, listOf(modify("A.txt", "b\n")), newSession())

      result shouldBe WriteResult.Ok(listOf("A.txt"))
      File(workTree, "A.txt").readText() shouldBe "b\n"
    }

    it("creates parent directories for an added file") {
      val workTree = createTempDirectory("wt").toFile()
      val add = PatchChange("deep/nested/New.txt", PatchChangeKind.ADD, "new\n".toByteArray(), FileMode.REGULAR_FILE)

      WorkTreePatchWriter().write(workTree, listOf(add), newSession())

      File(workTree, "deep/nested/New.txt").readText() shouldBe "new\n"
    }

    it("sets the executable bit from the post-image mode") {
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.sh").writeText("a\n")

      WorkTreePatchWriter().write(
        workTree,
        listOf(modify("A.sh", "b\n", FileMode.EXECUTABLE_FILE)),
        newSession(),
      )

      File(workTree, "A.sh").canExecute() shouldBe true
    }

    it("creates a symbolic link, not a regular file, for mode 120000") {
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.txt").writeText("a\n")
      val link = PatchChange("link", PatchChangeKind.ADD, "A.txt".toByteArray(), FileMode.SYMLINK)

      WorkTreePatchWriter().write(workTree, listOf(link), newSession())

      Files.isSymbolicLink(File(workTree, "link").toPath()) shouldBe true
      Files.readSymbolicLink(File(workTree, "link").toPath()).toString() shouldBe "A.txt"
    }

    it("deletes a path the patch removes") {
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.txt").writeText("a\n")

      WorkTreePatchWriter().write(
        workTree,
        listOf(PatchChange("A.txt", PatchChangeKind.DELETE, null, null)),
        newSession(),
      )

      File(workTree, "A.txt").exists() shouldBe false
    }

    it("restores every path it wrote when a later path fails, keeping content and mode") {
      val workTree = workTreeWithBlocker()
      File(workTree, "A.txt").writeText("a\n")
      File(workTree, "A.txt").setExecutable(true)
      val changes = listOf(modify("A.txt", "b\n", FileMode.EXECUTABLE_FILE), unwritable())

      val result = WorkTreePatchWriter().write(workTree, changes, newSession())

      result.shouldBeInstanceOf<WriteResult.RolledBack>()
      result.restored shouldBe 1
      result.notRestored shouldBe 0
      result.external shouldBe false
      File(workTree, "A.txt").readText() shouldBe "a\n"
      File(workTree, "A.txt").canExecute() shouldBe true
    }

    it("restores a deleted path from the backup area") {
      val workTree = workTreeWithBlocker()
      File(workTree, "A.txt").writeText("a\n")
      val changes = listOf(PatchChange("A.txt", PatchChangeKind.DELETE, null, null), unwritable())

      val result = WorkTreePatchWriter().write(workTree, changes, newSession())

      result.shouldBeInstanceOf<WriteResult.RolledBack>()
      result.restored shouldBe 1
      File(workTree, "A.txt").readText() shouldBe "a\n"
    }

    it("removes a file it added when a later path fails") {
      val workTree = workTreeWithBlocker()
      val add = PatchChange("New.txt", PatchChangeKind.ADD, "new\n".toByteArray(), FileMode.REGULAR_FILE)

      WorkTreePatchWriter().write(workTree, listOf(add, unwritable()), newSession())

      File(workTree, "New.txt").exists() shouldBe false
    }

    it("aborts before writing a path the working tree changed under it") {
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.txt").writeText("a\n")
      File(workTree, "B.txt").writeText("c\n")
      val changes = listOf(modify("A.txt", "b\n"), modify("B.txt", "d\n"))

      val result = WorkTreePatchWriter().write(workTree, changes, newSession()) { path ->
        if (path == "A.txt") File(workTree, "B.txt").writeText("external\n")
      }

      result.shouldBeInstanceOf<WriteResult.RolledBack>()
      result.external shouldBe true
      File(workTree, "A.txt").readText() shouldBe "a\n"
      File(workTree, "B.txt").readText() shouldBe "external\n"
    }

    it("does not restore a path an external process changed after we wrote it") {
      val workTree = workTreeWithBlocker()
      File(workTree, "A.txt").writeText("a\n")
      val changes = listOf(modify("A.txt", "b\n"), unwritable())

      val result = WorkTreePatchWriter().write(workTree, changes, newSession()) { path ->
        if (path == "A.txt") File(workTree, "A.txt").writeText("external\n")
      }

      result.shouldBeInstanceOf<WriteResult.RolledBack>()
      result.restored shouldBe 0
      result.notRestored shouldBe 1
      File(workTree, "A.txt").readText() shouldBe "external\n"
    }

    it("backs up the pre-image before overwriting it") {
      val workTree = createTempDirectory("wt").toFile()
      File(workTree, "A.txt").writeText("original\n")
      val session = newSession()

      WorkTreePatchWriter().write(workTree, listOf(modify("A.txt", "b\n")), session)
      File(workTree, "A.txt").writeText("whatever\n")

      session.restore("A.txt", workTree) shouldBe true
      File(workTree, "A.txt").readText() shouldBe "original\n"
    }
  }
})
