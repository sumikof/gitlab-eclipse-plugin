package com.gitlab.eclipse.utils

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor

/**
 * The active-editor resolution only. Everything here is an interface mock: no SWT `Display` is
 * touched, which is what lets these run headless.
 */
class PlatformUtilsTest : DescribeSpec({
  /** A workbench whose active window's active page reports [editor] (or no editor when null). */
  fun workbenchWithActiveEditor(editor: IEditorPart?): IWorkbench {
    val page = mockk<IWorkbenchPage> { every { activeEditor } returns editor }
    val window = mockk<IWorkbenchWindow> { every { activePage } returns page }
    return mockk<IWorkbench> {
      every { activeWorkbenchWindow } returns window
      every { workbenchWindows } returns arrayOf(window)
    }
  }

  fun platformUtilsFor(editor: IEditorPart?) = PlatformUtils { workbenchWithActiveEditor(editor) }

  describe("getActiveTextEditor") {
    it("returns the adapted editor for a multi-page editor that is not itself an ITextEditor") {
      // A form-based editor whose source tab is a text editor: the IEditorPart is not an
      // ITextEditor, but it adapts to one. A bare `as? ITextEditor` cast drops this case.
      val adapted = mockk<ITextEditor>()
      val multiPageEditor = mockk<IEditorPart> {
        every { getAdapter(ITextEditor::class.java) } returns adapted
      }

      platformUtilsFor(multiPageEditor).getActiveTextEditor() shouldBe adapted
    }

    it("falls back to the direct cast when the editor is an ITextEditor that does not adapt") {
      val textEditor = mockk<ITextEditor> {
        every { getAdapter(ITextEditor::class.java) } returns null
      }

      platformUtilsFor(textEditor).getActiveTextEditor() shouldBe textEditor
    }

    it("prefers the adapter over the direct cast when the editor both is and adapts") {
      val adapted = mockk<ITextEditor>()
      val textEditor = mockk<ITextEditor> {
        every { getAdapter(ITextEditor::class.java) } returns adapted
      }

      platformUtilsFor(textEditor).getActiveTextEditor() shouldBe adapted
    }

    it("returns null for an editor that is neither an ITextEditor nor adapts to one") {
      val editor = mockk<IEditorPart> {
        every { getAdapter(ITextEditor::class.java) } returns null
      }

      platformUtilsFor(editor).getActiveTextEditor() shouldBe null
    }

    it("returns null when no editor is active") {
      platformUtilsFor(null).getActiveTextEditor() shouldBe null
    }

    it("falls back to the first window with an active editor when there is no active window") {
      val adapted = mockk<ITextEditor>()
      val editor = mockk<IEditorPart> { every { getAdapter(ITextEditor::class.java) } returns adapted }
      val emptyPage = mockk<IWorkbenchPage> { every { activeEditor } returns null }
      val emptyWindow = mockk<IWorkbenchWindow> { every { activePage } returns emptyPage }
      val page = mockk<IWorkbenchPage> { every { activeEditor } returns editor }
      val window = mockk<IWorkbenchWindow> { every { activePage } returns page }
      val workbench = mockk<IWorkbench> {
        every { activeWorkbenchWindow } returns null
        every { workbenchWindows } returns arrayOf(emptyWindow, window)
      }

      PlatformUtils { workbench }.getActiveTextEditor() shouldBe adapted
    }
  }
})
