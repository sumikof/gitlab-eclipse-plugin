package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.ui.editors.text.StorageDocumentProvider

/**
 * AC-B1-5 headless: a changed job-log document never counts as savable, so the editor tab gets no
 * dirty marker and closing it asks nothing. Same real-provider approach as [JobLogEditorOpenerTest].
 */
class JobLogDocumentProviderTest : DescribeSpec({
  val key = JobLogKey.of("https://x.example/", "fp", 1L, 9L)

  fun <T> connected(provider: StorageDocumentProvider, input: JobLogEditorInput, block: () -> T): T {
    provider.connect(input)
    try {
      return block()
    } finally {
      provider.disconnect(input)
    }
  }

  describe("canSaveDocument") {
    it("stays false after the document changed") {
      val input = JobLogEditorInput(key, JobLogContent("trace"))
      val provider = JobLogDocumentProvider()
      connected(provider, input) {
        provider.getDocument(input).replace(0, 0, "edited ")
        provider.canSaveDocument(input) shouldBe false
        provider.mustSaveDocument(input) shouldBe false
      }
    }

    it("differs from the plain StorageDocumentProvider, which would mark the change savable") {
      val input = JobLogEditorInput(key, JobLogContent("trace"))
      val provider = StorageDocumentProvider()
      connected(provider, input) {
        provider.getDocument(input).replace(0, 0, "edited ")
        provider.canSaveDocument(input) shouldBe true
        provider.mustSaveDocument(input) shouldBe true
      }
    }
  }

  describe("resetDocument") {
    it("loads the text of the new rendered result and stays unsavable") {
      val content = JobLogContent("old")
      val input = JobLogEditorInput(key, content)
      val provider = JobLogDocumentProvider()
      connected(provider, input) {
        content.rendered = RenderResult("new 新\n", StyleRunIndex.EMPTY, emptyList(), omitted = false)
        provider.resetDocument(input)
        provider.getDocument(input).get() shouldBe "new 新\n"
        provider.canSaveDocument(input) shouldBe false
      }
    }
  }
})
