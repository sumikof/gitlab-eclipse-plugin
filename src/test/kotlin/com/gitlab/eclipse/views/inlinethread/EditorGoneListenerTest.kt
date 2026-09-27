package com.gitlab.eclipse.views.inlinethread

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.texteditor.ITextEditor

/** Which part events close an [InlineThreadPopup] (design §9.6 / FR-11), SWT-free. */
class EditorGoneListenerTest : DescribeSpec({
  describe("EditorGoneListener") {

    fun reference(part: ITextEditor?): IWorkbenchPartReference = mockk<IWorkbenchPartReference>().also {
      every { it.getPart(false) } returns part
    }

    it("reports the popup's editor as gone when its input changes (a reused editor)") {
      val editor = mockk<ITextEditor>()
      val own = reference(editor)
      var gone = 0
      EditorGoneListener(editor, own) { gone++ }.partInputChanged(own)
      gone shouldBe 1
    }

    it("matches an input change by the part when the reference was not captured") {
      val editor = mockk<ITextEditor>()
      var gone = 0
      EditorGoneListener(editor, null) { gone++ }.partInputChanged(reference(editor))
      gone shouldBe 1
    }

    it("reports the popup's editor as gone when it closes") {
      val editor = mockk<ITextEditor>()
      val own = reference(editor)
      var gone = 0
      EditorGoneListener(editor, own) { gone++ }.partClosed(own)
      gone shouldBe 1
    }

    it("ignores another editor's close and input change") {
      val editor = mockk<ITextEditor>()
      val other = reference(mockk<ITextEditor>())
      var gone = 0
      val listener = EditorGoneListener(editor, reference(editor)) { gone++ }
      listener.partClosed(other)
      listener.partInputChanged(other)
      gone shouldBe 0
    }

    it("ignores the other part events of its own editor") {
      val editor = mockk<ITextEditor>()
      val own = reference(editor)
      var gone = 0
      val listener = EditorGoneListener(editor, own) { gone++ }
      listener.partActivated(own)
      listener.partDeactivated(own)
      listener.partHidden(own)
      listener.partVisible(own)
      listener.partBroughtToTop(own)
      listener.partOpened(own)
      gone shouldBe 0
    }
  }
})
