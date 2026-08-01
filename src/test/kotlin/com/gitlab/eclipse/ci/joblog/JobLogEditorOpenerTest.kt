package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.ui.editors.text.StorageDocumentProvider

/**
 * Headless test for the ONE mechanism manual-refresh (FR-2/AC-2) hinges on: a
 * shared-mutable-content [JobLogEditorInput], when its document is reset through a real
 * [StorageDocumentProvider], yields the NEW text. This is exactly what
 * `JobLogEditorOpener.openOrReload` does to each open editor after updating the shared
 * [JobLogContent].
 *
 * The workbench-touching paths (openOrReload window iteration, U5 listener lifecycle,
 * disposeAtShutdown) cannot run headless and are manual-verified per the design.
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

        // Simulate openOrReload updating the shared content, then the exact call it makes
        // on each open editor.
        content.text = "new trace 新"
        provider.resetDocument(input)

        provider.getDocument(input).get() shouldBe "new trace 新"
      } finally {
        provider.disconnect(input)
      }
    }
  }
})
