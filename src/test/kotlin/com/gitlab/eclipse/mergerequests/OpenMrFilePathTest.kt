package com.gitlab.eclipse.mergerequests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class OpenMrFilePathTest : StringSpec({
  fun withTempTree(block: (workTree: File, outside: File) -> Unit) {
    val base = createTempDirectory("open-mr-file-path").toFile()
    try {
      val workTree = File(base, "worktree").apply { mkdir() }
      val outside = File(base, "outside").apply { mkdir() }
      block(workTree, outside)
    } finally {
      base.deleteRecursively()
    }
  }

  "a file inside the work tree resolves to its real path" {
    withTempTree { workTree, _ ->
      val file = File(workTree, "a.kt").apply { writeText("x") }

      val resolved = resolveContainedRealPath(workTree, "a.kt")

      resolved shouldBe file.toPath().toRealPath().toFile()
    }
  }

  "a nested file inside the work tree resolves" {
    withTempTree { workTree, _ ->
      File(workTree, "src/main").mkdirs()
      val file = File(workTree, "src/main/A.kt").apply { writeText("x") }

      val resolved = resolveContainedRealPath(workTree, "src/main/A.kt")

      resolved shouldBe file.toPath().toRealPath().toFile()
    }
  }

  "a ..-traversal path that escapes the work tree returns null even when the target exists" {
    withTempTree { workTree, outside ->
      File(outside, "secret.txt").writeText("secret")

      resolveContainedRealPath(workTree, "../outside/secret.txt").shouldBeNull()
    }
  }

  "an absolute path outside the work tree returns null even when the target exists" {
    withTempTree { workTree, outside ->
      val secret = File(outside, "secret.txt").apply { writeText("secret") }

      resolveContainedRealPath(workTree, secret.absolutePath).shouldBeNull()
    }
  }

  "a non-existent file returns null" {
    withTempTree { workTree, _ ->
      resolveContainedRealPath(workTree, "missing.kt").shouldBeNull()
    }
  }

  "a symlink inside the work tree pointing outside returns null" {
    withTempTree { workTree, outside ->
      val target = File(outside, "secret.txt").apply { writeText("secret") }
      try {
        Files.createSymbolicLink(File(workTree, "link.txt").toPath(), target.toPath())
      } catch (_: UnsupportedOperationException) {
        return@withTempTree // Symlinks unsupported on this filesystem; covered by the .. case.
      }

      resolveContainedRealPath(workTree, "link.txt").shouldBeNull()
    }
  }

  "a symlink inside the work tree pointing inside resolves to the link target's real path" {
    withTempTree { workTree, _ ->
      val target = File(workTree, "real.txt").apply { writeText("x") }
      try {
        Files.createSymbolicLink(File(workTree, "link.txt").toPath(), target.toPath())
      } catch (_: UnsupportedOperationException) {
        return@withTempTree
      }

      resolveContainedRealPath(workTree, "link.txt") shouldBe target.toPath().toRealPath().toFile()
    }
  }

  "a non-existent work tree returns null" {
    withTempTree { workTree, _ ->
      File(workTree, "a.kt").writeText("x")

      resolveContainedRealPath(File(workTree, "no-such-dir"), "a.kt").shouldBeNull()
    }
  }
})
