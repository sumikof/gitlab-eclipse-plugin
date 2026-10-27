package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.ui.editors.text.StorageDocumentProvider

/**
 * Headless tests of the shared-mutable-content [JobLogEditorInput]: when its document is reset
 * through a real [StorageDocumentProvider], it yields the NEW text.
 *
 * The workbench-touching paths (`JobLogEditorOpener.show`, `disposeAtShutdown`) cannot run
 * headless and are manual-verified per the design; the editors' lifetime is the session's
 * (`JobLogSessionRegistryTest`).
 */
class JobLogEditorOpenerTest : DescribeSpec({
  describe("document reset through a real StorageDocumentProvider") {
    it("re-reads the shared content so resetDocument shows the updated text (incl. UTF-8)") {
      val content = JobLogContent("old trace")
      val key = JobLogKey.of("https://x.example/", "fp", 1L, 9L)
      val input = JobLogEditorInput(key, content)
      val provider = StorageDocumentProvider()
      provider.connect(input)
      try {
        provider.getDocument(input).get() shouldBe "old trace"

        // Update the shared content, then reset the document from it.
        content.text = "new trace 新"
        provider.resetDocument(input)

        provider.getDocument(input).get() shouldBe "new trace 新"
      } finally {
        provider.disconnect(input)
      }
    }

    it("shows the text of a new RenderResult through the job-log provider after a reset") {
      val content = JobLogContent(RenderResult.EMPTY)
      val key = JobLogKey.of("https://x.example/", "fp", 1L, 9L)
      val input = JobLogEditorInput(key, content, "https://x.example/", "fp")
      val provider = JobLogDocumentProvider()
      provider.connect(input)
      try {
        provider.getDocument(input).get() shouldBe ""

        val (rendered, _) = renderInitial("first\nsecond\n".toByteArray(), omittedBytes = 0)
        content.rendered = rendered
        provider.resetDocument(input)

        provider.getDocument(input).get() shouldBe rendered.text
      } finally {
        provider.disconnect(input)
      }
    }
  }

  describe("JobLogContent") {
    it("text reads the rendered text, and setting it drops runs and folds") {
      val styled = RenderResult(
        "x\ny\n",
        StyleRunIndex.of(listOf(StyleRun(0, 1, AnsiStyle(bold = true)))),
        listOf(FoldRegion(0, 1, collapsedInitially = true)),
        omitted = false,
      )
      val content = JobLogContent(styled)
      content.text shouldBe "x\ny\n"

      content.text = "plain"

      content.rendered shouldBe RenderResult("plain", StyleRunIndex.EMPTY, emptyList(), omitted = false)
    }

    it("shows the log page unless a session says otherwise") {
      JobLogContent("trace").page shouldBe JobLogPage.Log
    }

    it("the wipe used on close leaves nothing behind") {
      val content = JobLogContent("trace body")
      content.rendered = RenderResult.EMPTY
      content.text shouldBe ""
      content.rendered.runs.size shouldBe 0
      content.rendered.folds shouldBe emptyList()
    }
  }
})
