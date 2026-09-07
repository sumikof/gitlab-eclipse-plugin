package com.gitlab.eclipse.lsp.edit

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.Document
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.text.edits.MalformedTreeException
import org.eclipse.text.edits.MultiTextEdit

private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
  Range(Position(startLine, startChar), Position(endLine, endChar))

class LspTextEditConverterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("toReplaceEdits") {
    it("returns an empty list for an empty edits list") {
      val document = Document("hello")
      LspTextEditConverter.toReplaceEdits(document, emptyList()).shouldBeEmpty()
    }

    describe("offset conversion") {
      it("converts a position at the start of a line") {
        val document = Document("line0\nline1\nline2")
        val edits = listOf(TextEdit(range(1, 0, 1, 0), "X"))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe document.getLineOffset(1)
      }

      it("converts a position at the end of a line, before the delimiter") {
        val document = Document("line0\nline1\nline2")
        // "line0" has length 5, delimiter excluded
        val edits = listOf(TextEdit(range(0, 5, 0, 5), "X"))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe 5
      }

      it("converts a position on the last line, which has no trailing delimiter") {
        val document = Document("line0\nline1\nline2")
        val edits = listOf(TextEdit(range(2, 5, 2, 5), "X"))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe document.getLineOffset(2) + 5
      }

      it("converts a position in an empty document") {
        val document = Document("")
        val edits = listOf(TextEdit(range(0, 0, 0, 0), "X"))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe 0
      }
    }

    describe("surrogate pairs") {
      it("counts an emoji as two UTF-16 code units, not one character") {
        // "a😀b" -> 'a' (1 unit), emoji (2 units, surrogate pair), 'b' (1 unit) = 4 units total
        val document = Document("a😀b")
        // character 3 must land right after the emoji, i.e. before 'b'
        val edits = listOf(TextEdit(range(0, 3, 0, 4), ""))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        val edit = result[0]
        edit.offset shouldBe 3
        edit.length shouldBe 1
        document.get(edit.offset, edit.length) shouldBe "b"
      }
    }

    describe("out of range") {
      it("throws BadLocationException when the line is beyond the document's last line") {
        val document = Document("line0\nline1")
        val edits = listOf(TextEdit(range(5, 0, 5, 0), "X"))
        shouldThrow<BadLocationException> {
          LspTextEditConverter.toReplaceEdits(document, edits)
        }
      }

      it("throws BadLocationException when the character is beyond the end of the line") {
        val document = Document("line0\nline1")
        // "line0" has length 5; character 6 is one past the end
        val edits = listOf(TextEdit(range(0, 6, 0, 6), "X"))
        shouldThrow<BadLocationException> {
          LspTextEditConverter.toReplaceEdits(document, edits)
        }
      }

      it("does not clamp an out-of-range character to the line end") {
        val document = Document("short\nline1")
        val edits = listOf(TextEdit(range(0, 100, 0, 100), "X"))
        shouldThrow<BadLocationException> {
          LspTextEditConverter.toReplaceEdits(document, edits)
        }
      }
    }

    describe("edit kinds") {
      it("converts a zero-length range to an insertion") {
        val document = Document("hello")
        val edits = listOf(TextEdit(range(0, 5, 0, 5), " world"))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe 5
        result[0].length shouldBe 0
        result[0].text shouldBe " world"
      }

      it("converts an empty newText to a deletion") {
        val document = Document("hello world")
        val edits = listOf(TextEdit(range(0, 5, 0, 11), ""))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe 5
        result[0].length shouldBe 6
        result[0].text shouldBe ""
      }

      it("converts a non-empty range with non-empty newText to a replacement") {
        val document = Document("hello world")
        val edits = listOf(TextEdit(range(0, 0, 0, 5), "goodbye"))
        val result = LspTextEditConverter.toReplaceEdits(document, edits)
        result[0].offset shouldBe 0
        result[0].length shouldBe 5
        result[0].text shouldBe "goodbye"
      }
    }

    describe("multiple edits") {
      it("preserves order and applies all edits correctly via a MultiTextEdit") {
        val document = Document("line0\nline1\nline2")
        val edits = listOf(
          TextEdit(range(2, 0, 2, 5), "LINE2"),
          TextEdit(range(0, 0, 0, 5), "LINE0"),
        )
        val result = LspTextEditConverter.toReplaceEdits(document, edits)

        // order preserved: first edit returned corresponds to first edit passed in
        result.map { it.text } shouldBe listOf("LINE2", "LINE0")

        val multiEdit = MultiTextEdit()
        result.forEach { multiEdit.addChild(it) }
        multiEdit.apply(document)

        document.get() shouldBe "LINE0\nline1\nLINE2"
      }
    }

    describe("overlapping edits") {
      it("throws MalformedTreeException and leaves the document unchanged") {
        val document = Document("hello world")
        val originalText = document.get()
        val edits = listOf(
          TextEdit(range(0, 0, 0, 7), "AAA"),
          TextEdit(range(0, 5, 0, 11), "BBB"),
        )
        val result = LspTextEditConverter.toReplaceEdits(document, edits)

        shouldThrow<MalformedTreeException> {
          val multiEdit = MultiTextEdit()
          result.forEach { multiEdit.addChild(it) }
          multiEdit.apply(document)
        }
        document.get() shouldBe originalText
      }
    }
  }
})
