package com.gitlab.eclipse.mergerequests.review

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.IDocumentListener

/** A real document that also counts its registered listeners, to prove the tracker unregisters itself. */
private class CountingDocument(text: String) : Document(text) {
  var listeners = 0

  override fun addDocumentListener(listener: IDocumentListener) {
    super.addDocumentListener(listener)
    listeners++
  }

  override fun removeDocumentListener(listener: IDocumentListener) {
    super.removeDocumentListener(listener)
    listeners--
  }
}

class NewThreadEditTrackerTest : DescribeSpec({
  describe("NewThreadEditTracker (Codex r5)") {
    it("stays unedited while the document is untouched") {
      val document = CountingDocument("a\nb\nc\n")
      val tracker = NewThreadEditTracker(document).also { it.install() }

      tracker.edited shouldBe false
      document.listeners shouldBe 1
    }

    it("is edited after any change, and stays edited when the change is undone") {
      val document = CountingDocument("a\nb\nc\n")
      val tracker = NewThreadEditTracker(document).also { it.install() }

      document.replace(0, 0, "new\n")
      tracker.edited shouldBe true
      document.replace(0, 4, "")
      tracker.edited shouldBe true
    }

    it("removes its listener on dispose (idempotent) and reports edited from then on (fail closed)") {
      val document = CountingDocument("a\nb\nc\n")
      val tracker = NewThreadEditTracker(document).also { it.install() }

      tracker.dispose()
      tracker.dispose()
      document.listeners shouldBe 0
      tracker.edited shouldBe true
    }

    it("without a document installs nothing and reports unedited (the submit-time check refuses then)") {
      val tracker = NewThreadEditTracker(null).also { it.install() }

      tracker.edited shouldBe false
      tracker.dispose()
      tracker.edited shouldBe true
    }
  }
})
