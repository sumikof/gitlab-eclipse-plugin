package com.gitlab.eclipse.chat.quickchat.fix

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class FixRangeTest : DescribeSpec({

  /** Builds an IDocument-like line lookup over [text] (delimiters \n, \r\n excluded from line length). */
  fun lineSpans(text: String): (Int) -> TextSpan {
    val starts = mutableListOf(0)
    val lengths = mutableListOf<Int>()
    var i = 0
    var lineStart = 0
    while (i < text.length) {
      when {
        text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
          lengths += i - lineStart; i += 2; lineStart = i; starts += lineStart
        }
        text[i] == '\n' -> {
          lengths += i - lineStart; i += 1; lineStart = i; starts += lineStart
        }
        else -> i++
      }
    }
    lengths += text.length - lineStart
    return lineSpanOf(
      lineOfOffset = { offset -> starts.indexOfLast { it <= offset } },
      lineOffset = { line -> starts[line] },
      lineLengthWithoutDelimiter = { line -> lengths[line] },
    )
  }

  data class Case(
    val name: String,
    val text: String,
    val selection: TextSpan?,
    val target: TextSpan,
    val expected: TextSpan,
  )

  describe("fixRange") {
    withData(
      nameFn = { it.name },
      Case("non-empty selection wins", "", TextSpan(5, 3), TextSpan(20, 4), TextSpan(5, 3)),
      Case("zero-length selection is ignored", "", TextSpan(5, 0), TextSpan(20, 4), TextSpan(20, 4)),
      Case("zero-length target widens to its line without CRLF", "ab\r\ncd\r\nef", null, TextSpan(5, 0), TextSpan(4, 2)),
      Case("last line without delimiter is not truncated", "ab\ncd", null, TextSpan(4, 0), TextSpan(3, 2)),
      Case("empty line yields an empty span", "ab\n\ncd", null, TextSpan(3, 0), TextSpan(3, 0)),
      Case("non-empty selection wins over a zero-length target", "ab\ncd", TextSpan(0, 2), TextSpan(4, 0), TextSpan(0, 2)),
      Case("null selection with a sized target returns the target", "", null, TextSpan(20, 4), TextSpan(20, 4)),
    ) { c ->
      fixRange(c.selection, c.target, lineSpans(c.text)) shouldBe c.expected
    }
  }

  describe("TextSpan.end") {
    it("is offset plus length") {
      TextSpan(3, 4).end shouldBe 7
    }
  }
})
