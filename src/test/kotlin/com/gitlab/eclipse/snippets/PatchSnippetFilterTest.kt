package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.api.SnippetBlob
import com.gitlab.eclipse.api.SnippetSummary
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PatchSnippetFilterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun snippet(id: String, vararg paths: String) =
    SnippetSummary(id, "title-$id", "", paths.map { SnippetBlob(it.substringAfterLast('/'), it) })

  describe("candidates") {
    it("keeps only blobs whose path ends with .patch") {
      val snippets = listOf(snippet("1", "a.patch", "b.txt"), snippet("2", "c.txt"))

      val result = PatchSnippetFilter.candidates(snippets)

      result.map { it.blob.path } shouldBe listOf("a.patch")
      result.single().snippet.id shouldBe "1"
    }

    it("matches the extension case-insensitively") {
      PatchSnippetFilter.candidates(listOf(snippet("1", "A.PATCH"))).size shouldBe 1
    }

    it("emits one candidate per patch blob when a snippet holds several") {
      val result = PatchSnippetFilter.candidates(listOf(snippet("1", "a.patch", "b.patch")))

      result.map { it.blob.path } shouldBe listOf("a.patch", "b.patch")
    }

    it("does not treat a bare .patch directory-like path as a match on the name alone") {
      PatchSnippetFilter.candidates(listOf(snippet("1", "patch"))) shouldBe emptyList()
    }

    it("returns an empty list when nothing is a patch") {
      PatchSnippetFilter.candidates(listOf(snippet("1", "a.txt"))) shouldBe emptyList()
    }

    it("returns an empty list for a snippet with no files") {
      PatchSnippetFilter.candidates(listOf(snippet("1"))) shouldBe emptyList()
    }
  }

  describe("label") {
    it("names the snippet and the file so two patches in one snippet stay distinguishable") {
      val candidate = PatchSnippetFilter.candidates(listOf(snippet("1", "a.patch"))).single()

      candidate.label shouldBe "title-1 — a.patch"
    }
  }
})
