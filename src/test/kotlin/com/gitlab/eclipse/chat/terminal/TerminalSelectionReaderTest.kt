package com.gitlab.eclipse.chat.terminal

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.TextSelection
import org.eclipse.jface.viewers.StructuredSelection

/**
 * Stand-ins for the Terminal view's `CTabItem` and its `VT100TerminalControl`. Public top-level
 * classes on purpose: the reader looks the methods up reflectively, as it must for the real ones,
 * which live in bundles this plug-in does not depend on.
 */
class FakeTerminalTab(private val data: Any?) {
  fun getData(): Any? = data
}

/** The terminal control of the `org.eclipse.terminal.*` generation (Eclipse 2025-09 and later). */
class FakeTerminalControl(private val selection: String?) {
  fun getSelection(): String? = selection
}

/** The terminal control of the `org.eclipse.tm.terminal.*` generation (CDT, before 2025-09). */
class FakeLegacyTerminalControl(private val selection: String?) {
  fun getSelection(): String? = selection
}

class FakeThrowingTab {
  fun getData(): Any? = error("Widget is disposed")
}

class FakeThrowingControl {
  fun getSelection(): String = error("secret-in-message")
}

class FakeNonStringControl(private val selection: Any = 42) {
  fun getSelection(): Any = selection
}

class TerminalSelectionReaderTest : DescribeSpec({

  describe("Console (ITextSelection)") {
    it("reads the selected text") {
      val document = Document("line 1\nerror: boom\nline 3")
      val selection = TextSelection(document, 7, 11)

      TerminalSelectionReader.read(selection) shouldBe TerminalSelection.Text("error: boom")
    }

    it("treats an empty selection as no selection") {
      TerminalSelectionReader.read(TextSelection(Document("abc"), 1, 0)) shouldBe TerminalSelection.Empty
    }

    it("treats a whitespace-only selection as no selection") {
      TerminalSelectionReader.read(TextSelection(Document("a \n\t b"), 1, 4)) shouldBe TerminalSelection.Empty
    }

    it("treats a selection without a document as no selection") {
      TerminalSelectionReader.read(TextSelection(0, 3)) shouldBe TerminalSelection.Empty
    }
  }

  describe("Terminal (the menu selection holds the tab, not the text)") {
    it("reads the text of the new generation's control") {
      val selection = StructuredSelection(FakeTerminalTab(FakeTerminalControl("$ make\nerror 2")))

      TerminalSelectionReader.read(selection) shouldBe TerminalSelection.Text("$ make\nerror 2")
    }

    it("reads the text of the legacy generation's control the same way") {
      val selection = StructuredSelection(FakeTerminalTab(FakeLegacyTerminalControl("legacy output")))

      TerminalSelectionReader.read(selection) shouldBe TerminalSelection.Text("legacy output")
    }

    it("treats the control's empty string as no selection") {
      TerminalSelectionReader.read(StructuredSelection(FakeTerminalTab(FakeTerminalControl("")))) shouldBe
        TerminalSelection.Empty
    }

    it("treats a null selection from the control as no selection") {
      TerminalSelectionReader.read(StructuredSelection(FakeTerminalTab(FakeTerminalControl(null)))) shouldBe
        TerminalSelection.Empty
    }

    it("is unavailable when the tab carries no control") {
      TerminalSelectionReader.read(StructuredSelection(FakeTerminalTab(null)))
        .shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }

    it("is unavailable when the tab's data has no getSelection()") {
      TerminalSelectionReader.read(StructuredSelection(FakeTerminalTab(Any())))
        .shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }

    it("is unavailable when getSelection() does not return a String") {
      TerminalSelectionReader.read(StructuredSelection(FakeTerminalTab(FakeNonStringControl())))
        .shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }

    it("is unavailable, not failing, when the tab throws (e.g. disposed)") {
      TerminalSelectionReader.read(StructuredSelection(FakeThrowingTab()))
        .shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }

    it("reports only type names when the control throws, never the exception message") {
      val result = TerminalSelectionReader.read(StructuredSelection(FakeTerminalTab(FakeThrowingControl())))

      result.shouldBeInstanceOf<TerminalSelection.Unavailable>()
      result.reason shouldNotContain "secret-in-message"
    }
  }

  describe("anything else") {
    it("is unavailable for a null selection") {
      TerminalSelectionReader.read(null).shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }

    it("is unavailable for an empty structured selection") {
      TerminalSelectionReader.read(StructuredSelection.EMPTY).shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }

    it("does not treat a bare String element as terminal text (it may be stale)") {
      TerminalSelectionReader.read(StructuredSelection("stale text"))
        .shouldBeInstanceOf<TerminalSelection.Unavailable>()
    }
  }
})
