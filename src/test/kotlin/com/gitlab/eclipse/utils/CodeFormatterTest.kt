package com.gitlab.eclipse.utils

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.eclipse.core.runtime.Platform
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.TextSelection

/**
 * The argument version of [CodeFormatter.format] (Quick Chat, design §8.3 / §9.6). JDT's formatter
 * runs headless; only the editor preferences it reads (tab width, spaces for tabs) are stubbed.
 */
class CodeFormatterTest : DescribeSpec({
  val formatter = CodeFormatter(mockk(relaxed = true))

  beforeSpec {
    mockkStatic(Platform::class)
    every { Platform.getPreferencesService() } returns mockk {
      every { getInt("org.eclipse.ui.editors", "tabWidth", any(), null) } returns 4
      every { getBoolean("org.eclipse.ui.editors", "spacesForTabs", any(), null) } returns true
    }
  }

  afterSpec { unmockkStatic(Platform::class) }

  describe("format(snippet, document, selection)") {
    it("returns a single-line snippet trimmed at both ends when the caret's line is blank") {
      val document = Document("class A {\n    \n}\n")
      formatter.format("  foo();  \n", document, TextSelection(document, 12, 0)) shouldBe "foo();"
    }

    it("keeps a single-line snippet's leading whitespace when the caret's line has text") {
      val document = Document("int x = ;\n")
      formatter.format("  1 \n\n", document, TextSelection(document, 8, 0)) shouldBe "  1"
    }

    it("uses the given selection's start, not the active editor's") {
      val document = Document("text\n\nmore\n")
      formatter.format("\t  a  ", document, TextSelection(document, 5, 0)) shouldBe "a"
      formatter.format("\t  a  ", document, TextSelection(document, 0, 4)) shouldBe "\t  a"
    }

    it("indents a multi-line snippet's following lines to the insertion point, like Duo Chat's insert") {
      val document = Document("class A {\n  void m() {\n    \n  }\n}\n")
      val caretOnBlankLine = TextSelection(document, 27, 0)
      formatter.format("if (x) {\ny();\n}\n", document, caretOnBlankLine) shouldBe
        "if (x) {\n        y();\n    }"
    }
  }
})
