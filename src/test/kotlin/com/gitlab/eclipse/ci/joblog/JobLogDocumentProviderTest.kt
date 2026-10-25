package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.eclipse.jface.text.source.IAnnotationModelExtension
import org.eclipse.ui.editors.text.StorageDocumentProvider
import org.eclipse.ui.texteditor.IElementStateListener

// A lone surrogate does not survive a trip through UTF-8 bytes (it comes back as '?'), so the
// document holding it proves the text was set directly instead of re-read from JobLogStorage.
private const val UNENCODABLE = "log \uD800 tail\n"

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

  fun rendered(text: String) = RenderResult(text, StyleRunIndex.EMPTY, emptyList(), omitted = false)

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

  // Review C-1: without a model the ProjectionViewer has no projection model and nothing folds.
  describe("annotation model") {
    it("gives a connected job log a model the viewer can attach the projection model to") {
      val input = JobLogEditorInput(key, JobLogContent("trace"))
      val provider = JobLogDocumentProvider()
      connected(provider, input) {
        provider.getAnnotationModel(input).shouldNotBeNull().shouldBeInstanceOf<IAnnotationModelExtension>()
      }
    }
  }

  // Review I-2: the rendered String goes into the document as is.
  describe("createDocument") {
    it("takes the rendered text without a UTF-8 round trip") {
      val input = JobLogEditorInput(key, JobLogContent(rendered(UNENCODABLE)))
      val provider = JobLogDocumentProvider()
      connected(provider, input) {
        provider.getDocument(input).get() shouldBe UNENCODABLE
      }
    }

    it("differs from the plain StorageDocumentProvider, which re-reads the storage bytes") {
      val input = JobLogEditorInput(key, JobLogContent(rendered(UNENCODABLE)))
      val provider = StorageDocumentProvider()
      connected(provider, input) {
        provider.getDocument(input).get() shouldNotBe UNENCODABLE
      }
    }
  }

  describe("resetDocument") {
    it("loads the text of the new rendered result and stays unsavable") {
      val content = JobLogContent("old")
      val input = JobLogEditorInput(key, content)
      val provider = JobLogDocumentProvider()
      connected(provider, input) {
        content.rendered = rendered("new 新\n")
        provider.resetDocument(input)
        provider.getDocument(input).get() shouldBe "new 新\n"
        provider.canSaveDocument(input) shouldBe false
      }
    }

    it("sets the open document directly, without a UTF-8 round trip") {
      val content = JobLogContent("old")
      val input = JobLogEditorInput(key, content)
      val provider = JobLogDocumentProvider()
      connected(provider, input) {
        val document = provider.getDocument(input)
        content.rendered = rendered(UNENCODABLE)
        provider.resetDocument(input)
        provider.getDocument(input) shouldBeSameInstanceAs document
        document.get() shouldBe UNENCODABLE
      }
    }

    it("tells the editor the content was replaced and is not dirty, like the base provider") {
      val content = JobLogContent("old")
      val input = JobLogEditorInput(key, content)
      val provider = JobLogDocumentProvider()
      val events = mutableListOf<String>()
      provider.addElementStateListener(object : IElementStateListener {
        override fun elementDirtyStateChanged(element: Any?, isDirty: Boolean) {
          events += "dirty=$isDirty"
        }

        override fun elementContentAboutToBeReplaced(element: Any?) {
          events += "aboutToBeReplaced"
        }

        override fun elementContentReplaced(element: Any?) {
          events += "replaced"
        }

        override fun elementDeleted(element: Any?) {
          events += "deleted"
        }

        override fun elementMoved(originalElement: Any?, movedElement: Any?) {
          events += "moved"
        }
      })
      connected(provider, input) {
        content.rendered = rendered("new\n")
        provider.resetDocument(input)
      }
      events shouldContainExactly listOf("aboutToBeReplaced", "dirty=true", "replaced", "dirty=false")
    }
  }
})
