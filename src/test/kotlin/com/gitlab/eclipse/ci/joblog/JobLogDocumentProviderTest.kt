package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.spyk
import io.mockk.verify
import org.eclipse.core.runtime.IStatus
import org.eclipse.jface.text.source.IAnnotationModelExtension
import org.eclipse.ui.editors.text.StorageDocumentProvider
import org.eclipse.ui.texteditor.IElementStateListener
import java.time.LocalTime

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

    it("does not build a second document: createDocument runs only on connect") {
      // The base doResetDocument calls createDocument again and copies its text over, which keeps
      // both the text and the document instance the same, so only the call count tells them apart.
      val content = JobLogContent("old")
      val input = JobLogEditorInput(key, content)
      val provider = spyk(JobLogDocumentProvider(), recordPrivateCalls = true)
      connected(provider, input) {
        content.rendered = rendered("new\n")
        provider.resetDocument(input)
        provider.getDocument(input).get() shouldBe "new\n"
      }
      verify(exactly = 1) { provider["createDocument"](input) }
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

  // The editor's page comes from the provider's status (design B3).
  describe("getStatus") {
    fun statusFor(page: JobLogPage?): IStatus {
      val content = JobLogContent("trace")
      page?.let { content.page = it }
      return JobLogDocumentProvider().getStatus(JobLogEditorInput(key, content))
    }

    it("is OK for the log, the default page") {
      statusFor(null).isOK shouldBe true
      statusFor(JobLogPage.Log).isOK shouldBe true
    }

    it("is an INFO page status with its own code for every other page") {
      listOf(
        JobLogPage.Loading to JobLogDocumentProvider.LOADING_CODE,
        JobLogPage.Waiting("pending", LocalTime.NOON) to JobLogDocumentProvider.WAITING_CODE,
        JobLogPage.Unavailable("why") to JobLogDocumentProvider.UNAVAILABLE_CODE,
        JobLogPage.Erased to JobLogDocumentProvider.ERASED_CODE,
      ).forEach { (page, code) ->
        val status = statusFor(page)
        status.severity shouldBe IStatus.INFO
        status.code shouldBe code
        status.plugin shouldBe JobLogDocumentProvider.PLUGIN_ID
        JobLogDocumentProvider.isPageStatus(status) shouldBe true
      }
      JobLogDocumentProvider.isPageStatus(statusFor(JobLogPage.Log)) shouldBe false
    }

    it("follows the content of each input, read at call time") {
      val waiting = JobLogContent("a").apply { page = JobLogPage.Loading }
      val other = JobLogContent("b")
      val provider = JobLogDocumentProvider()
      val input = JobLogEditorInput(key, waiting)

      provider.getStatus(input).code shouldBe JobLogDocumentProvider.LOADING_CODE
      provider.getStatus(JobLogEditorInput(JobLogKey.of("https://y.example/", "fp", 1L, 9L), other)).isOK shouldBe true

      waiting.page = JobLogPage.Erased
      provider.getStatus(input).code shouldBe JobLogDocumentProvider.ERASED_CODE
    }
  }

  // L-B2-1 (A): one provider, so equal inputs (same key) share one document.
  describe("SHARED") {
    it("gives two inputs of the same key the same document") {
      val content = JobLogContent("shared\n")
      val first = JobLogEditorInput(key, content)
      val second = JobLogEditorInput(key, content)
      val provider = JobLogDocumentProvider.SHARED
      connected(provider, first) {
        connected(provider, second) {
          provider.getDocument(second) shouldBeSameInstanceAs provider.getDocument(first)
        }
      }
    }
  }
})
