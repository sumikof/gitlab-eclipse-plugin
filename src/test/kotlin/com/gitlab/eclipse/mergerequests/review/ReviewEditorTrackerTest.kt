package com.gitlab.eclipse.mergerequests.review

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.source.IVerticalRulerInfo
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.IWorkbenchPartSite
import org.eclipse.ui.texteditor.ITextEditor

/**
 * The part-listener side of [ReviewEditorTracker] (design §9.6, FR-11), SWT-free: the editors have
 * no ruler control, so no widget is created and only the page listener is exercised.
 */
private class TrackerFixture {
  val released = mutableListOf<Pair<ITextEditor, IDocument>>()
  val tracker = ReviewEditorTracker(
    onEditorClosed = { editor, document -> released += editor to document },
    onRulerClick = { _, _, _ -> },
  )
  val page = mockk<IWorkbenchPage>(relaxUnitFun = true)
  private val listener = slot<IPartListener2>()

  init {
    every { page.addPartListener(capture(listener)) } returns Unit
  }

  /** The page listener the tracker registered (after the first [track]). */
  val partListener: IPartListener2 get() = listener.captured

  fun editorWithReference(): Pair<ITextEditor, IWorkbenchPartReference> {
    val editor = mockk<ITextEditor>()
    val site = mockk<IWorkbenchPartSite>()
    val reference = mockk<IWorkbenchPartReference>()
    every { editor.site } returns site
    every { site.page } returns page
    every { page.getReference(editor) } returns reference
    every { editor.getAdapter(IVerticalRulerInfo::class.java) } returns null
    every { reference.getPart(false) } returns editor
    return editor to reference
  }
}

class ReviewEditorTrackerTest : DescribeSpec({
  describe("ReviewEditorTracker part listener") {

    it("releases a tracked editor whose input changed, with the document it was tracked under") {
      val fixture = TrackerFixture()
      val (editor, reference) = fixture.editorWithReference()
      val document = mockk<IDocument>()
      fixture.tracker.track(editor, document)

      fixture.partListener.partInputChanged(reference)

      fixture.released shouldContainExactly listOf(editor to document)
      fixture.tracker.documentOf(editor).shouldBeNull()
      verify(exactly = 1) { fixture.page.removePartListener(fixture.partListener) }
    }

    it("releases the editor only once when the input change is followed by the part's close") {
      val fixture = TrackerFixture()
      val (editor, reference) = fixture.editorWithReference()
      val document = mockk<IDocument>()
      fixture.tracker.track(editor, document)

      fixture.partListener.partInputChanged(reference)
      fixture.partListener.partClosed(reference)

      fixture.released shouldContainExactly listOf(editor to document)
    }

    it("keeps the other tracked editors of the page when one editor's input changed") {
      val fixture = TrackerFixture()
      val (reused, reusedReference) = fixture.editorWithReference()
      val (other, _) = fixture.editorWithReference()
      val document = mockk<IDocument>()
      fixture.tracker.track(reused, document)
      fixture.tracker.track(other, document)

      fixture.partListener.partInputChanged(reusedReference)

      fixture.released shouldContainExactly listOf(reused to document)
      fixture.tracker.documentOf(other) shouldBeSameInstanceAs document
      verify(exactly = 0) { fixture.page.removePartListener(any<IPartListener2>()) }
    }

    it("ignores an input change of a part it does not track") {
      val fixture = TrackerFixture()
      val (editor, _) = fixture.editorWithReference()
      val document = mockk<IDocument>()
      fixture.tracker.track(editor, document)
      val (_, untrackedReference) = fixture.editorWithReference()

      fixture.partListener.partInputChanged(untrackedReference)

      fixture.released.shouldBeEmpty()
      fixture.tracker.documentOf(editor) shouldBeSameInstanceAs document
    }

    it("still releases a tracked editor on the part's close") {
      val fixture = TrackerFixture()
      val (editor, reference) = fixture.editorWithReference()
      val document = mockk<IDocument>()
      fixture.tracker.track(editor, document)

      fixture.partListener.partClosed(reference)

      fixture.released shouldContainExactly listOf(editor to document)
    }
  }
})
