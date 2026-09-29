package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.ContextResult
import com.gitlab.eclipse.chat.quickchat.TooLargeItem
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.TextSelection
import org.eclipse.jface.viewers.StructuredSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IURIEditorInput
import org.eclipse.ui.editors.text.ILocationProvider
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.ITextEditor
import java.io.File
import java.net.URI

private const val TEXT = "line one\nline two\nline three\n"

/** 1-based anchor line of [offset]/[length] in [TEXT], through the same seam production uses. */
private fun anchorIn(text: String, offset: Int, length: Int): Int {
  val document = Document(text)
  return anchorLine(document::getLineOfOffset, document::getLineOffset, offset, length)
}

private fun editorOn(input: IEditorInput, document: Document?, selection: Any?): ITextEditor {
  val provider = mockk<IDocumentProvider>()
  every { provider.getDocument(input) } returns document
  val selectionProvider = mockk<org.eclipse.jface.viewers.ISelectionProvider>()
  every { selectionProvider.selection } returns selection as org.eclipse.jface.viewers.ISelection?
  return mockk {
    every { editorInput } returns input
    every { documentProvider } returns provider
    every { this@mockk.selectionProvider } returns selectionProvider
  }
}

private fun plainInput(name: String = "Scratch.kt"): IEditorInput = mockk {
  every { getAdapter(any<Class<*>>()) } returns null
  every { this@mockk.name } returns name
}

class QuickChatContextCaptureTest : DescribeSpec({
  describe("anchorLine (I5)") {
    it("an empty selection anchors on the caret's line") {
      anchorIn(TEXT, offset = 12, length = 0) shouldBe 2
    }

    it("a caret at the very start anchors on line 1") {
      anchorIn(TEXT, offset = 0, length = 0) shouldBe 1
    }

    it("a caret at a line start stays on that line (only selections step back)") {
      anchorIn(TEXT, offset = 9, length = 0) shouldBe 2
    }

    it("a selection anchors on the line of its end") {
      anchorIn(TEXT, offset = 2, length = 12) shouldBe 2
    }

    it("a selection ending exactly at a line start anchors on the previous line") {
      // "line one\n" selected as a whole line: its end offset 9 is column 0 of line 2.
      anchorIn(TEXT, offset = 0, length = 9) shouldBe 1
      // Lines 1–2 whole.
      anchorIn(TEXT, offset = 0, length = 18) shouldBe 2
    }

    it("a selection ending at the end of the document without a trailing newline stays there") {
      anchorIn("a\nb", offset = 0, length = 3) shouldBe 2
    }

    it("a selection ending at the empty last line after a trailing newline steps back") {
      anchorIn(TEXT, offset = 18, length = TEXT.length - 18) shouldBe 3
    }
  }

  describe("quickChatFileName (§9.2.1)") {
    it("uses the workspace path without its leading slash") {
      quickChatFileName("/project/src/Foo.java", File("/home/me/ws/project/src/Foo.java"), "Foo.java") shouldBe
        "project/src/Foo.java"
    }

    it("never sends an absolute path: outside the workspace only the file name") {
      quickChatFileName(null, File("/home/me/secret/Bar.kt"), "whatever") shouldBe "Bar.kt"
    }

    it("falls back to the input's name") {
      quickChatFileName(null, null, "Untitled 1") shouldBe "Untitled 1"
    }

    it("is null when nothing names the input") {
      quickChatFileName(null, null, null).shouldBeNull()
    }

    it("keeps only the last segment of a name that looks like a path") {
      quickChatFileName(null, null, "/tmp/x/Baz.kt") shouldBe "Baz.kt"
    }
  }

  describe("anchorFileOf (§9.2.1)") {
    val workspace = File("/ws/p/A.kt")
    val provided = File("/ext/B.kt")

    it("prefers the workspace file's location") {
      anchorFileOf(workspace, provided, URI("file:/other/C.kt")) shouldBe workspace
    }

    it("then the location provider's path") {
      anchorFileOf(null, provided, URI("file:/other/C.kt")) shouldBe provided
    }

    it("then a file: URI") {
      anchorFileOf(null, null, URI("file:/other/C.kt")) shouldBe File("/other/C.kt")
    }

    it("has no location for a non-file URI or a file: URI that is no plain path") {
      anchorFileOf(null, null, URI("jar:file:/x.jar!/C.class")).shouldBeNull()
      anchorFileOf(null, null, URI("file://host/share/C.kt")).shouldBeNull()
      anchorFileOf(null, null, null).shouldBeNull()
    }
  }

  describe("DocumentTextWindow") {
    it("reads windows of the document") {
      val window = DocumentTextWindow(Document(TEXT))
      window.length shouldBe TEXT.length
      window.get(5, 3) shouldBe "one"
    }
  }

  describe("captureContext (the popup's editor, §9.2.1)") {
    it("sends the selection with the workspace-relative name and the workspace location") {
      val file = mockk<IFile> {
        every { fullPath } returns Path("/proj/src/A.kt")
        every { location } returns Path("/home/me/ws/proj/src/A.kt")
      }
      val input = mockk<IEditorInput> {
        every { getAdapter(IFile::class.java) } returns file
        every { getAdapter(ILocationProvider::class.java) } returns null
        every { name } returns "A.kt"
      }
      val editor = editorOn(input, Document(TEXT), TextSelection(9, 8))

      val captured = captureContext(editor, "what?")

      captured.anchorFile shouldBe File("/home/me/ws/proj/src/A.kt")
      val current = captured.result.shouldBeInstanceOf<ContextResult.Ok>().context.currentFile.shouldNotBeNull()
      current.fileName shouldBe "proj/src/A.kt"
      current.selectedText shouldBe "line two"
      current.contentAboveCursor shouldBe "line one\n"
      current.contentBelowCursor shouldBe "\nline three\n"
    }

    it("reads an external file's location through its location provider, sending only its name") {
      val provider = mockk<ILocationProvider>()
      val input = mockk<IEditorInput> {
        every { getAdapter(IFile::class.java) } returns null
        every { getAdapter(ILocationProvider::class.java) } returns provider
        every { name } returns "B.kt"
      }
      every { provider.getPath(input) } returns Path("/ext/dir/B.kt")
      val editor = editorOn(input, Document(TEXT), TextSelection(0, 4))

      val captured = captureContext(editor, "q")

      captured.anchorFile shouldBe File("/ext/dir/B.kt")
      val current = captured.result.shouldBeInstanceOf<ContextResult.Ok>().context.currentFile.shouldNotBeNull()
      current.fileName shouldBe "B.kt"
    }

    it("reads a URI input's file location") {
      val input = mockk<IURIEditorInput> {
        every { getAdapter(any<Class<*>>()) } returns null
        every { uri } returns URI("file:/u/C.kt")
        every { name } returns "C.kt"
      }
      val editor = editorOn(input, Document(TEXT), TextSelection(0, 0))

      captureContext(editor, "q").anchorFile shouldBe File("/u/C.kt")
    }

    it("sends no file for an empty selection") {
      val editor = editorOn(plainInput(), Document(TEXT), TextSelection(3, 0))
      val captured = captureContext(editor, "q")
      captured.result.shouldBeInstanceOf<ContextResult.Ok>().context.currentFile.shouldBeNull()
      captured.anchorFile.shouldBeNull()
    }

    it("sends no file without a text selection or without a document") {
      captureContext(editorOn(plainInput(), Document(TEXT), StructuredSelection.EMPTY), "q").result
        .shouldBeInstanceOf<ContextResult.Ok>().context.currentFile.shouldBeNull()
      captureContext(editorOn(plainInput(), null, TextSelection(0, 4)), "q").result
        .shouldBeInstanceOf<ContextResult.Ok>().context.currentFile.shouldBeNull()
    }

    it("reports a too-long question") {
      val editor = editorOn(plainInput(), Document(TEXT), TextSelection(0, 0))
      captureContext(editor, "x".repeat(16 * 1024 + 1)).result shouldBe ContextResult.TooLarge(TooLargeItem.QUESTION)
    }
  }

  describe("anchorLineOf (the handler's entry)") {
    it("reads the popup editor's document and selection") {
      anchorLineOf(editorOn(plainInput(), Document(TEXT), TextSelection(0, 9))) shouldBe 1
      anchorLineOf(editorOn(plainInput(), Document(TEXT), TextSelection(20, 0))) shouldBe 3
    }

    it("is null without a document or a text selection") {
      anchorLineOf(editorOn(plainInput(), null, TextSelection(0, 0))).shouldBeNull()
      anchorLineOf(editorOn(plainInput(), Document(TEXT), StructuredSelection.EMPTY)).shouldBeNull()
    }

    it("is null for a selection outside the document") {
      anchorLineOf(editorOn(plainInput(), Document(TEXT), TextSelection(500, 3))).shouldBeNull()
    }
  }
})
