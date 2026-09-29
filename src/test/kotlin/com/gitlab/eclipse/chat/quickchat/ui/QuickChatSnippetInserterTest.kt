package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.ClipboardTarget
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.views.inlinethread.CodeBlockAction
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.content.IContentDescription
import org.eclipse.core.runtime.content.IContentType
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.TextSelection
import org.eclipse.jface.viewers.ISelectionProvider
import org.eclipse.text.undo.IDocumentUndoManager
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IURIEditorInput
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.ITextEditor
import org.eclipse.ui.texteditor.ITextEditorExtension2
import java.io.File
import java.net.URI

private interface EditableEditor : ITextEditor, ITextEditorExtension2

/** An editor on [document] with [selection]; [editable] answers `validateEditorInputState`. */
private fun editorOn(document: IDocument?, selection: Any?, editable: Boolean = true): EditableEditor {
  val input = mockk<IEditorInput>()
  val provider = mockk<IDocumentProvider> { every { getDocument(input) } returns document }
  val selectionProvider = mockk<ISelectionProvider> {
    every { this@mockk.selection } returns selection as org.eclipse.jface.viewers.ISelection?
  }
  return mockk(relaxUnitFun = true) {
    every { editorInput } returns input
    every { documentProvider } returns provider
    every { this@mockk.selectionProvider } returns selectionProvider
    every { validateEditorInputState() } returns editable
  }
}

private fun contentType(id: String, base: IContentType? = null): IContentType = mockk {
  every { this@mockk.id } returns id
  every { baseType } returns base
}

class QuickChatSnippetInserterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("isJavaSource") {
    it("is Java for a .java file, whatever the case of the extension") {
      isJavaSource("java", emptyList()) shouldBe true
      isJavaSource("JAVA", emptyList()) shouldBe true
    }

    it("is Java for JDT's Java source content type, also as a base type") {
      isJavaSource(null, listOf("org.eclipse.jdt.core.javaSource")) shouldBe true
      isJavaSource("jav", listOf("custom.java", "org.eclipse.jdt.core.javaSource")) shouldBe true
    }

    it("is not Java otherwise") {
      isJavaSource("kt", listOf("org.eclipse.core.runtime.text")) shouldBe false
      isJavaSource(null, emptyList()) shouldBe false
    }
  }

  describe("fileExtensionOf (inputs without a workspace file)") {
    it("takes the location provider's path first") {
      fileExtensionOf(File("/tmp/A.java"), URI("file:/tmp/B.kt"), "C.txt") shouldBe "java"
    }

    it("then the URI's last path segment") {
      fileExtensionOf(null, URI("file:/tmp/dir.d/B.java"), "C.txt") shouldBe "java"
      fileExtensionOf(null, URI("jar:file:/x.jar!/p/B.java"), "C.txt") shouldBe "txt" // opaque URI: no path
    }

    it("then the input's name") {
      fileExtensionOf(null, null, "Main.JAVA") shouldBe "JAVA"
    }

    it("is null without a dot in the chosen name or without any name") {
      fileExtensionOf(File("/tmp/Makefile"), null, "C.java") shouldBe null
      fileExtensionOf(null, null, "name.") shouldBe null
      fileExtensionOf(null, null, null) shouldBe null
    }
  }

  describe("isJavaEditor") {
    fun editorWith(input: IEditorInput): ITextEditor = mockk { every { editorInput } returns input }

    it("decides a non-workspace input by its name's extension") {
      val input = mockk<IURIEditorInput> {
        every { getAdapter(any<Class<*>>()) } returns null
        every { uri } returns URI("file:/tmp/Outside.java")
        every { name } returns "Outside.java"
      }
      isJavaEditor(editorWith(input)) shouldBe true
    }

    it("is not Java for a non-workspace input with another extension") {
      val input = mockk<IEditorInput> {
        every { getAdapter(any<Class<*>>()) } returns null
        every { name } returns "notes.md"
      }
      isJavaEditor(editorWith(input)) shouldBe false
    }

    it("prefers the workspace file when there is one") {
      val file = mockk<IFile> {
        every { fileExtension } returns "kt"
        every { contentDescription } returns null
      }
      val input = mockk<IEditorInput> {
        every { getAdapter(IFile::class.java) } returns file
        every { name } returns "Looks.java"
      }
      isJavaEditor(editorWith(input)) shouldBe false
    }
  }

  describe("contentTypeIdsOf") {
    it("lists the file's content type and its base types, nearest first") {
      val text = contentType("org.eclipse.core.runtime.text")
      val java = contentType("org.eclipse.jdt.core.javaSource", text)
      val file = mockk<IFile> {
        every { contentDescription } returns mockk<IContentDescription> { every { this@mockk.contentType } returns java }
      }
      contentTypeIdsOf(file) shouldContainExactly listOf("org.eclipse.jdt.core.javaSource", "org.eclipse.core.runtime.text")
    }

    it("is empty when the file has no description or cannot be read") {
      contentTypeIdsOf(mockk { every { contentDescription } returns null }).shouldBeEmpty()
      contentTypeIdsOf(
        mockk { every { contentDescription } throws CoreException(Status.CANCEL_STATUS) },
      ).shouldBeEmpty()
    }
  }

  describe("snippetToInsert") {
    it("inserts the formatter's result when there is one") {
      snippetToInsert("  a();\n", formatted = "a();") shouldBe "a();"
    }

    it("only trims the code without a formatter result (not Java, or the formatter failed)") {
      snippetToInsert("\n  a();\n  b();  \n\n", formatted = null) shouldBe "a();\n  b();"
    }

    it("only trims the code when the formatter returned a blank result") {
      snippetToInsert("  a();  ", formatted = " \n ") shouldBe "a();"
    }
  }

  describe("QuickChatSnippetInserter.insert") {
    val notices = mutableListOf<String>()
    lateinit var undo: IDocumentUndoManager
    lateinit var formatter: CodeFormatter

    fun inserter(java: Boolean = false) = QuickChatSnippetInserter(
      codeFormatter = formatter,
      notify = { notices += it },
      undoManagerOf = { undo },
      isJava = { java },
    )

    beforeEach {
      notices.clear()
      undo = mockk(relaxUnitFun = true)
      formatter = mockk()
    }

    it("replaces the selection with the trimmed code as one undoable change and puts the caret after it (I7)") {
      val document = Document("val x = OLD\n")
      val editor = editorOn(document, TextSelection(document, 8, 3))

      inserter().insert(editor, "  42  \n")

      document.get() shouldBe "val x = 42\n"
      verifyOrder {
        undo.beginCompoundChange()
        undo.endCompoundChange()
        editor.selectAndReveal(10, 0)
      }
      notices.shouldBeEmpty()
    }

    it("inserts at the caret for an empty selection") {
      val document = Document("ab")
      val editor = editorOn(document, TextSelection(document, 1, 0))

      inserter().insert(editor, "X")

      document.get() shouldBe "aXb"
      verify { editor.selectAndReveal(2, 0) }
    }

    it("formats Java with the popup's document and selection") {
      val document = Document("class A {}\n")
      val selection = TextSelection(document, 9, 0)
      val editor = editorOn(document, selection)
      val passedDocument = slot<IDocument>()
      val passedSelection = slot<ITextSelection>()
      every { formatter.format("x", capture(passedDocument), capture(passedSelection)) } returns "FORMATTED"

      inserter(java = true).insert(editor, "x")

      passedDocument.captured shouldBe document
      passedSelection.captured shouldBe selection
      document.get() shouldBe "class A {FORMATTED}\n"
    }

    it("falls back to the trimmed code when the Java formatter throws") {
      val document = Document("")
      val editor = editorOn(document, TextSelection(document, 0, 0))
      every { formatter.format(any(), any(), any()) } throws NullPointerException()

      inserter(java = true).insert(editor, "  y();  ")

      document.get() shouldBe "y();"
      notices.shouldBeEmpty()
    }

    it("never formats code for a non-Java document") {
      val document = Document("")
      inserter(java = false).insert(editorOn(document, TextSelection(document, 0, 0)), "z")
      verify(exactly = 0) { formatter.format(any(), any(), any()) }
    }

    it("stops with a notice and leaves the document alone when the editor cannot be edited") {
      val document = mockk<IDocument>()
      val editor = editorOn(document, TextSelection(0, 0), editable = false)

      inserter().insert(editor, "code")

      notices shouldContainExactly listOf("This editor cannot be edited.")
      verify(exactly = 0) { document.replace(any(), any(), any()) }
      verify(exactly = 0) { undo.beginCompoundChange() }
      verify(exactly = 0) { editor.selectAndReveal(any(), any()) }
    }

    it("ends the compound change and notifies without code when the replace fails") {
      val document = Document("short")
      val editor = editorOn(document, TextSelection(99, 0)) // past the end: BadLocationException

      inserter().insert(editor, "secret code")

      verifyOrder {
        undo.beginCompoundChange()
        undo.endCompoundChange()
      }
      notices shouldContainExactly listOf("Could not insert the code.")
      notices.single() shouldNotContain "secret"
      document.get() shouldBe "short"
      verify(exactly = 0) { editor.selectAndReveal(any(), any()) }
    }

    it("still replaces the text when the document has no undo manager") {
      val document = Document("a")
      val editor = editorOn(document, TextSelection(document, 1, 0))
      QuickChatSnippetInserter(formatter, { notices += it }, { null }, { false }).insert(editor, "b")
      document.get() shouldBe "ab"
    }

    it("notifies a failure when the editor has no document or no text selection") {
      inserter().insert(editorOn(null, TextSelection(0, 0)), "c")
      inserter().insert(editorOn(Document(""), null), "c")
      notices shouldContainExactly listOf("Could not insert the code.", "Could not insert the code.")
    }

    it("keeps an inserted text when only revealing the caret fails") {
      val document = Document("")
      val editor = editorOn(document, TextSelection(document, 0, 0))
      every { editor.selectAndReveal(any(), any()) } throws IllegalStateException()

      inserter().insert(editor, "d")

      document.get() shouldBe "d"
      notices.shouldBeEmpty()
    }
  }

  describe("QuickChatCodeActions") {
    val notices = mutableListOf<String>()
    val copied = mutableListOf<String>()
    val inserted = mutableListOf<Pair<ITextEditor, String>>()
    val editor = mockk<ITextEditor>()
    val clipboard = object : ClipboardTarget {
      override fun setText(text: String) {
        copied += text
      }

      override fun dispose() = Unit
    }
    val actions = QuickChatCodeActions(
      insert = { target, code -> inserted += target to code },
      clipboard = ClipboardWriter(onUiThread = { it.run() }, openClipboard = { clipboard }),
      notify = { notices += it },
    )

    beforeEach {
      notices.clear()
      copied.clear()
      inserted.clear()
    }

    it("copies the block's code and says so (design §9.8)") {
      actions.perform(editor, CodeBlockAction.COPY, "fun a() = 1")
      copied shouldContainExactly listOf("fun a() = 1")
      notices shouldContainExactly listOf("Code copied to clipboard.")
      inserted.shouldBeEmpty()
    }

    it("inserts the block's code into the popup's editor") {
      actions.perform(editor, CodeBlockAction.INSERT, "fun a() = 1")
      inserted shouldContainExactly listOf(editor to "fun a() = 1")
      copied.shouldBeEmpty()
    }
  }
})
