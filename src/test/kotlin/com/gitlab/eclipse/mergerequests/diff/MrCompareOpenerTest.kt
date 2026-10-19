package com.gitlab.eclipse.mergerequests.diff

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.compare.CompareConfiguration
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow

/** The workbench is mocked; nothing here needs a Display. */
class MrCompareOpenerTest : DescribeSpec({
  beforeSpec { stubHeadlessCompare() }
  afterSpec { releaseHeadlessCompare() }

  fun inputFor(content: MrFileDiffContent): MrCompareEditorInput {
    val headless = CompareConfiguration(null as IPreferenceStore?)
    return MrCompareEditorInput(content, headless)
  }

  val compareId = MrCompareOpener.COMPARE_EDITOR_ID

  fun ref(id: String, input: IEditorInput?, materialized: Boolean = true): Pair<IEditorReference, IEditorPart?> {
    val part = if (materialized) mockk<IEditorPart> { every { editorInput } returns input } else null
    val ref = mockk<IEditorReference> {
      every { this@mockk.id } returns id
      every { getEditor(false) } returns part
    }
    return ref to part
  }

  fun page(vararg refs: IEditorReference): IWorkbenchPage = mockk(relaxed = true) {
    every { editorReferences } returns arrayOf(*refs)
  }

  val content = compareContent()
  val key = content.request.key

  describe("activateExisting") {
    it("activates the compare editor showing the key and says so") {
      val otherFile = inputFor(compareContent(compareRequest(newPath = "src/B.kt")))
      val (other, _) = ref(compareId, otherFile)
      val (match, part) = ref(compareId, inputFor(content))
      val page = page(other, match)

      MrCompareOpener.activateExisting(key, page) shouldBe true

      verify(exactly = 1) { page.activate(part) }
    }

    it("is false, activating nothing, when no compare editor shows the key") {
      val otherSnapshot = inputFor(compareContent(compareRequest(head = "c".repeat(40))))
      val (other, _) = ref(compareId, otherSnapshot)
      val page = page(other)

      MrCompareOpener.activateExisting(key, page) shouldBe false

      verify(exactly = 0) { page.activate(any()) }
    }

    it("ignores other editor kinds and never restores an editor that is not materialized") {
      val (text, _) = ref("org.eclipse.ui.DefaultTextEditor", inputFor(content))
      val (lazyRef, _) = ref(compareId, null, materialized = false)
      val page = page(text, lazyRef)

      MrCompareOpener.activateExisting(key, page) shouldBe false

      verify(exactly = 0) { text.getEditor(any()) }
      verify(exactly = 0) { lazyRef.getEditor(true) }
    }
  }

  describe("closeAll") {
    fun workbench(closing: Boolean, vararg pages: IWorkbenchPage): IWorkbench {
      val window = mockk<IWorkbenchWindow> { every { this@mockk.pages } returns arrayOf(*pages) }
      return mockk {
        every { isClosing } returns closing
        every { workbenchWindows } returns arrayOf(window)
      }
    }

    it("closes only the MR compare editors, without saving") {
      val (mine, _) = ref(compareId, inputFor(content))
      val (foreign, _) = ref(compareId, mockk<IEditorInput>())
      val (text, _) = ref("org.eclipse.ui.DefaultTextEditor", mockk<IEditorInput>())
      val page = page(mine, foreign, text)

      MrCompareOpener.closeAllIn(workbench(closing = false, page))

      verify(exactly = 1) { page.closeEditors(arrayOf(mine), false) }
    }

    it("closes nothing while the workbench is closing (the editors go with it)") {
      val (mine, _) = ref(compareId, inputFor(content))
      val page = page(mine)

      MrCompareOpener.closeAllIn(workbench(closing = true, page))

      verify(exactly = 0) { page.closeEditors(any(), any()) }
    }

    it("does nothing without a running workbench") {
      MrCompareOpener.closeAllIn(null)
    }

    it("the production entry point is a no-op headless") {
      MrCompareOpener.closeAll()
    }
  }

  describe("open (F5)") {
    it("runs the input before handing it to Compare, so Compare starts no Job and no modal context") {
      val page = mockk<IWorkbenchPage>()
      var handed: MrCompareEditorInput? = null
      var resultWhenHanded: Any? = null

      MrCompareOpener.open(
        content,
        page,
        configuration = { CompareConfiguration(null as IPreferenceStore?) },
        openOnPage = { input, onPage ->
          onPage shouldBe page
          handed = input as MrCompareEditorInput
          resultWhenHanded = input.compareResult
        },
      )

      handed!!.key shouldBe key
      handed!!.canRunAsJob() shouldBe true
      (resultWhenHanded is org.eclipse.compare.structuremergeviewer.DiffNode) shouldBe true
    }
  }
})
