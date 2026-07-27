package com.gitlab.eclipse.mergerequests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class RepositoryContextTest : StringSpec({
  "encodeProjectId replaces only the path separator, not existing percent-escapes" {
    RepositoryContext.encodeProjectId("gr%C3%BCp/proj") shouldBe "gr%C3%BCp%2Fproj"
  }
  "encodeProjectId replaces a simple two-segment path" {
    RepositoryContext.encodeProjectId("a/b") shouldBe "a%2Fb"
  }
  "encodeProjectId leaves a single segment untouched" {
    RepositoryContext.encodeProjectId("single") shouldBe "single"
  }
  "dedupByCanonicalPath collapses two File references to the same canonical path" {
    val tempDir = createTempDirectory("repository-context-dedup").toFile()
    try {
      val direct = tempDir
      val indirect = File(tempDir, "./")

      val result = RepositoryContext.dedupByCanonicalPath(listOf(direct, indirect))

      result.size shouldBe 1
    } finally {
      tempDir.delete()
    }
  }
  "dedupByCanonicalPath preserves distinct paths and first-encountered order" {
    val base = createTempDirectory("repository-context-dedup-distinct").toFile()
    try {
      val first = File(base, "one")
      val second = File(base, "two")
      first.mkdir()
      second.mkdir()

      val result = RepositoryContext.dedupByCanonicalPath(listOf(second, first, second))

      result shouldContainExactly listOf(second, first)
    } finally {
      base.deleteRecursively()
    }
  }
})
