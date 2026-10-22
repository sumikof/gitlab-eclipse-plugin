package com.gitlab.eclipse.currentbranch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor
import java.io.File

private fun inputOf(path: String?): IEditorInput {
  val input = mockk<IEditorInput>(relaxed = true)
  val file = path?.let { p -> mockk<IFile>(relaxed = true).also { every { it.location } returns Path(p) } }
  every { input.getAdapter(IFile::class.java) } returns file
  return input
}

private fun textEditor(path: String?): ITextEditor =
  mockk<ITextEditor>(relaxed = true).also {
    every { it.editorInput } returns inputOf(path)
    every { it.getAdapter(ITextEditor::class.java) } returns null
  }

private fun windowWith(editor: IEditorPart?): IWorkbenchWindow {
  val page = mockk<IWorkbenchPage>(relaxed = true)
  every { page.activeEditor } returns editor
  return mockk<IWorkbenchWindow>(relaxed = true).also { every { it.activePage } returns page }
}

class WindowEditorFileReaderTest : StringSpec({
  "R16: reads THIS window's active text editor file" {
    WindowEditorFileReader.activeEditorFile(windowWith(textEditor("/a/src/A.kt"))) shouldBe File("/a/src/A.kt")
  }

  "a multi-page editor that adapts to a text editor is read through the adapter (PlatformUtils semantics)" {
    val multiPage = mockk<IEditorPart>(relaxed = true)
    every { multiPage.getAdapter(ITextEditor::class.java) } returns textEditor("/b/pom.xml")

    WindowEditorFileReader.activeEditorFile(windowWith(multiPage)) shouldBe File("/b/pom.xml")
  }

  "no page, no editor, a non-text editor or an input without a workspace file read as null" {
    val noPage = mockk<IWorkbenchWindow>(relaxed = true).also { every { it.activePage } returns null }
    val nonText = mockk<IEditorPart>(relaxed = true).also {
      every { it.getAdapter(ITextEditor::class.java) } returns null
    }

    WindowEditorFileReader.activeEditorFile(noPage).shouldBeNull()
    WindowEditorFileReader.activeEditorFile(windowWith(null)).shouldBeNull()
    WindowEditorFileReader.activeEditorFile(windowWith(nonText)).shouldBeNull()
    WindowEditorFileReader.activeEditorFile(windowWith(textEditor(null))).shouldBeNull()
  }

  "a workbench that throws (window closing) reads as null" {
    val closing = mockk<IWorkbenchWindow>()
    every { closing.activePage } throws IllegalStateException("closing")

    WindowEditorFileReader.activeEditorFile(closing).shouldBeNull()
  }

  "fileOf reads an activated editor part directly" {
    WindowEditorFileReader.fileOf(textEditor("/c/C.kt")) shouldBe File("/c/C.kt")
    WindowEditorFileReader.fileOf(null).shouldBeNull()
  }
})
