package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.spyk
import io.mockk.verify

private const val QUESTION_LIMIT_BYTES = 16 * 1024
private const val SELECTION_LIMIT_CHARS = 64 * 1024
private const val ADJACENT_LIMIT_CHARS = 32 * 1024

/** A [TextWindow] over a plain in-memory string — mirrors what `IDocument` would provide (PR-2). */
private class FakeTextWindow(private val content: String) : TextWindow {
  override val length: Int get() = content.length

  override fun get(offset: Int, length: Int): String = content.substring(offset, offset + length)
}

class QuickChatContextBuilderTest : DescribeSpec({

  describe("QuickChatContextBuilder.build — no selection (design §6.4 R3, A6)") {
    it("sends no currentFile when the selection is empty") {
      val text = FakeTextWindow("fun main() {}")
      val result = QuickChatContextBuilder.build(
        question = "what does this do?",
        fileName = "Foo.kt",
        text = text,
        selectionOffset = 3,
        selectionLength = 0,
      )
      result shouldBe ContextResult.Ok(QuickChatContext("what does this do?", currentFile = null))
    }

    it("sends no currentFile when there is no text (no editor window)") {
      val result = QuickChatContextBuilder.build(
        question = "hello",
        fileName = null,
        text = null,
        selectionOffset = 0,
        selectionLength = 5,
      )
      result shouldBe ContextResult.Ok(QuickChatContext("hello", currentFile = null))
    }
  }

  describe("QuickChatContextBuilder.build — with a selection") {
    it("fills currentFile from the selection and surrounding text") {
      val text = FakeTextWindow("before SELECTED after")
      val result = QuickChatContextBuilder.build(
        question = "q",
        fileName = "src/Foo.kt",
        text = text,
        selectionOffset = 7,
        selectionLength = 8,
      )
      result shouldBe ContextResult.Ok(
        QuickChatContext(
          "q",
          CurrentFile(
            fileName = "src/Foo.kt",
            selectedText = "SELECTED",
            contentAboveCursor = "before ",
            contentBelowCursor = " after",
          ),
        ),
      )
    }
  }

  describe("QuickChatContextBuilder.build — question size (A21)") {
    it("accepts a question of exactly 16 KiB (ASCII, boundary)") {
      val question = "a".repeat(QUESTION_LIMIT_BYTES)
      val result = QuickChatContextBuilder.build(question, null, null, 0, 0)
      result shouldBe ContextResult.Ok(QuickChatContext(question, currentFile = null))
    }

    it("rejects a question 1 byte over 16 KiB (multibyte char pushes it over without adding a char)") {
      // 16383 ASCII bytes + one 2-byte char = 16385 bytes, 16384 chars.
      val question = "a".repeat(QUESTION_LIMIT_BYTES - 1) + "é"
      val result = QuickChatContextBuilder.build(question, null, null, 0, 0)
      result shouldBe ContextResult.TooLarge(TooLargeItem.QUESTION)
    }
  }

  describe("QuickChatContextBuilder.build — selection size (A21)") {
    it("accepts a selection of exactly 64 Ki characters / 64 KiB (ASCII, boundary)") {
      val selected = "s".repeat(SELECTION_LIMIT_CHARS)
      val text = spyk(FakeTextWindow(selected))
      val result = QuickChatContextBuilder.build("q", "F.kt", text, 0, SELECTION_LIMIT_CHARS)
      result shouldBe ContextResult.Ok(
        QuickChatContext(
          "q",
          CurrentFile(fileName = "F.kt", selectedText = selected, contentAboveCursor = "", contentBelowCursor = ""),
        ),
      )
    }

    it("rejects a selection over 64 Ki characters WITHOUT reading it") {
      val text = spyk(FakeTextWindow("x".repeat(SELECTION_LIMIT_CHARS + 1)))
      val result = QuickChatContextBuilder.build("q", "F.kt", text, 0, SELECTION_LIMIT_CHARS + 1)
      result shouldBe ContextResult.TooLarge(TooLargeItem.SELECTION)
      verify(exactly = 0) { text.get(any(), any()) }
    }

    it("rejects a selection 1 byte over 64 KiB even though its char count is exactly at the char boundary") {
      // 65535 ASCII bytes + one 2-byte char = 65537 bytes, but only 65536 chars (at the char limit).
      val selected = "s".repeat(SELECTION_LIMIT_CHARS - 1) + "é"
      selected.length shouldBe SELECTION_LIMIT_CHARS
      val text = spyk(FakeTextWindow(selected))
      val result = QuickChatContextBuilder.build("q", "F.kt", text, 0, selected.length)
      result shouldBe ContextResult.TooLarge(TooLargeItem.SELECTION)
    }
  }

  describe("QuickChatContextBuilder.build — above/below truncation (A21: near side kept, code point boundary)") {
    it("keeps at most 32 KiB of context above, dropping from the FAR (leading) end, whole code points only") {
      // above = one 4-byte emoji (surrogate pair, 2 chars) + 32766 ASCII 'a' = 32768 chars / 32770 bytes.
      // Over budget by 2 bytes: the emoji must be dropped whole, not split into a lone surrogate.
      val emoji = "😀" // U+1F600, 4 bytes in UTF-8
      val above = emoji + "a".repeat(ADJACENT_LIMIT_CHARS - 2)
      above.length shouldBe ADJACENT_LIMIT_CHARS
      val selected = "X"
      val below = ""
      val text = FakeTextWindow(above + selected + below)

      val result = QuickChatContextBuilder.build("q", "F.kt", text, above.length, selected.length)

      val currentFile = (result as ContextResult.Ok).context.currentFile!!
      currentFile.contentAboveCursor shouldBe "a".repeat(ADJACENT_LIMIT_CHARS - 2)
      currentFile.contentAboveCursor.toByteArray(Charsets.UTF_8).size shouldBe ADJACENT_LIMIT_CHARS - 2
    }

    it("keeps at most 32 KiB of context below, dropping from the FAR (trailing) end, whole code points only") {
      val emoji = "😀"
      val below = "a".repeat(ADJACENT_LIMIT_CHARS - 2) + emoji
      below.length shouldBe ADJACENT_LIMIT_CHARS
      val selected = "X"
      val above = ""
      val text = FakeTextWindow(above + selected + below)

      val result = QuickChatContextBuilder.build("q", "F.kt", text, above.length, selected.length)

      val currentFile = (result as ContextResult.Ok).context.currentFile!!
      currentFile.contentBelowCursor shouldBe "a".repeat(ADJACENT_LIMIT_CHARS - 2)
      currentFile.contentBelowCursor.toByteArray(Charsets.UTF_8).size shouldBe ADJACENT_LIMIT_CHARS - 2
    }

    it("does not read more than 32 Ki characters of context on either side") {
      val above = "a".repeat(ADJACENT_LIMIT_CHARS + 500)
      val below = "b".repeat(ADJACENT_LIMIT_CHARS + 500)
      val selected = "X"
      val text = spyk(FakeTextWindow(above + selected + below))

      val result = QuickChatContextBuilder.build("q", "F.kt", text, above.length, selected.length)

      val currentFile = (result as ContextResult.Ok).context.currentFile!!
      currentFile.contentAboveCursor.length shouldBe ADJACENT_LIMIT_CHARS
      currentFile.contentBelowCursor.length shouldBe ADJACENT_LIMIT_CHARS
      verify { text.get(above.length - ADJACENT_LIMIT_CHARS, ADJACENT_LIMIT_CHARS) }
      verify { text.get(above.length + selected.length, ADJACENT_LIMIT_CHARS) }
    }

    it("clamps to the document's edges when less than 32 Ki characters are available on either side") {
      val above = "before"
      val selected = "X"
      val below = "after"
      val text = FakeTextWindow(above + selected + below)

      val result = QuickChatContextBuilder.build("q", "F.kt", text, above.length, selected.length)

      val currentFile = (result as ContextResult.Ok).context.currentFile!!
      currentFile.contentAboveCursor shouldBe "before"
      currentFile.contentBelowCursor shouldBe "after"
    }
  }

  describe(
    "QuickChatContextBuilder.build — a surrogate pair straddles the CHARACTER-offset read window " +
      "(controller review round 1: the 32 Ki char window itself, not just the later byte trim, must " +
      "never split a code point)",
  ) {
    it("drops a lone low surrogate left at the far (leading) edge of the above window") {
      // doc = "P" + high-surrogate + low-surrogate + 32767 'a'. The window is exactly
      // ADJACENT_LIMIT_CHARS chars, read from aboveStart = 2 (right at the low surrogate), so
      // text.get() itself returns a string starting with a lone low surrogate. Its UTF-8 byte
      // length under toByteArray() (~ADJACENT_LIMIT_CHARS bytes, the surrogate counts as 1 byte)
      // is within the 32 KiB budget, so keepSuffix would return it unchanged if not sanitized first.
      val highSurrogate = "\uD83D"
      val lowSurrogate = "\uDE00"
      val above = "P" + highSurrogate + lowSurrogate + "a".repeat(ADJACENT_LIMIT_CHARS - 1)
      above.length shouldBe ADJACENT_LIMIT_CHARS + 2
      val selected = "X"
      val text = FakeTextWindow(above + selected)

      val result = QuickChatContextBuilder.build("q", "F.kt", text, above.length, selected.length)

      val currentFile = (result as ContextResult.Ok).context.currentFile!!
      // A strict equality against a pure-ASCII expectation is itself the proof the lone low
      // surrogate is gone — any leftover surrogate would make this a mismatch, not a "close" pass.
      currentFile.contentAboveCursor shouldBe "a".repeat(ADJACENT_LIMIT_CHARS - 1)
    }

    it("drops a lone high surrogate left at the far (trailing) edge of the below window") {
      // doc(below) = 32767 'a' + high-surrogate + low-surrogate + "Q". The window is exactly
      // ADJACENT_LIMIT_CHARS chars starting at 0, so it ends right after the high surrogate,
      // leaving it dangling with no matching low surrogate in the returned string.
      val highSurrogate = "\uD83D"
      val lowSurrogate = "\uDE00"
      val below = "a".repeat(ADJACENT_LIMIT_CHARS - 1) + highSurrogate + lowSurrogate + "Q"
      below.length shouldBe ADJACENT_LIMIT_CHARS + 2
      val selected = "X"
      val text = FakeTextWindow(selected + below)

      val result = QuickChatContextBuilder.build("q", "F.kt", text, 0, selected.length)

      val currentFile = (result as ContextResult.Ok).context.currentFile!!
      currentFile.contentBelowCursor shouldBe "a".repeat(ADJACENT_LIMIT_CHARS - 1)
    }
  }
})
