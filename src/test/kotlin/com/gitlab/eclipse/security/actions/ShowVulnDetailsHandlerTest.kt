package com.gitlab.eclipse.security.actions

import com.gitlab.eclipse.lsp.LanguageServerHandle
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.viewers.ISelectionProvider
import org.eclipse.jface.viewers.StructuredSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.texteditor.ITextEditor
import java.net.URI

class ShowVulnDetailsHandlerTest : DescribeSpec({
  val handle = mockk<LanguageServerHandle>()

  fun fileInput(uri: String?): IFileEditorInput {
    val file = mockk<IFile> { every { locationURI } returns uri?.let { URI(it) } }
    return mockk { every { this@mockk.file } returns file }
  }

  fun textSelection(startLine: Int): ITextSelection = mockk { every { this@mockk.startLine } returns startLine }

  fun textEditor(input: IEditorInput?, selection: Any?): ITextEditor {
    val provider = mockk<ISelectionProvider> {
      every { this@mockk.selection } answers { selection as org.eclipse.jface.viewers.ISelection? }
    }
    return mockk {
      every { getAdapter(ITextEditor::class.java) } returns null
      every { editorInput } returns input
      every { selectionProvider } returns provider
    }
  }

  fun decide(editor: IEditorPart?, h: LanguageServerHandle? = handle) = decideVulnDetails(editor, h)

  describe("decideVulnDetails") {
    it("shows the finding on the 1-based cursor line of the normalized file path") {
      val editor = textEditor(fileInput("file:/home/u/p/Main.java"), textSelection(41))

      val decision = decide(editor).shouldBeTypeOf<VulnDetailsDecision.Show>()

      decision.path shouldBe "/home/u/p/Main.java"
      decision.line shouldBe 42
      decision.handle shouldBeSameInstanceAs handle
    }

    it("keys the file exactly as the scan launcher does, drive letter included") {
      val editor = textEditor(fileInput("file:/c:/p/Main.java"), textSelection(0))

      val decision = decide(editor).shouldBeTypeOf<VulnDetailsDecision.Show>()

      decision.path shouldBe "/C:/p/Main.java"
      decision.line shouldBe 1
    }

    it("reaches the text editor through the adapter when the part is not one itself") {
      val inner = textEditor(fileInput("file:/p/A.kt"), textSelection(2))
      val part = mockk<IEditorPart> { every { getAdapter(ITextEditor::class.java) } returns inner }

      decide(part).shouldBeTypeOf<VulnDetailsDecision.Show>().line shouldBe 3
    }

    it("asks for a text editor when there is no active editor") {
      decide(null) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the active editor is not a text editor") {
      val part = mockk<IEditorPart> { every { getAdapter(ITextEditor::class.java) } returns null }

      decide(part) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the input is not a workspace file") {
      val editor = textEditor(mockk<IEditorInput>(), textSelection(1))

      decide(editor) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the file has no location on disk") {
      decide(textEditor(fileInput(null), textSelection(1))) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the file is not on the local file system") {
      val editor = textEditor(fileInput("jar:file:/a.jar!/B.java"), textSelection(1))

      decide(editor) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the selection is not a text selection") {
      val editor = textEditor(fileInput("file:/p/A.kt"), StructuredSelection.EMPTY)

      decide(editor) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the text selection has no line") {
      decide(textEditor(fileInput("file:/p/A.kt"), textSelection(-1))) shouldBe
        VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("asks for a text editor when the editor has no selection provider") {
      val editor = mockk<ITextEditor> {
        every { getAdapter(ITextEditor::class.java) } returns null
        every { editorInput } returns fileInput("file:/p/A.kt")
        every { selectionProvider } returns null
      }

      decide(editor) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("says the language server is not running when there is no connection, and shows nothing") {
      val editor = textEditor(fileInput("file:/p/A.kt"), textSelection(1))

      decide(editor, h = null) shouldBe VulnDetailsDecision.Notify(LANGUAGE_SERVER_NOT_RUNNING_MESSAGE)
    }

    it("reports the missing editor before the missing connection") {
      decide(null, h = null) shouldBe VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
    }

    it("uses the exact user-facing messages") {
      NO_TEXT_EDITOR_MESSAGE shouldBe "Open a file in a text editor to show GitLab vulnerability details."
      LANGUAGE_SERVER_NOT_RUNNING_MESSAGE shouldBe "The GitLab Language Server is not running."
    }
  }
})
