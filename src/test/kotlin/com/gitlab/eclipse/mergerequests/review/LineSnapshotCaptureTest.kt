package com.gitlab.eclipse.mergerequests.review

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.charset.StandardCharsets

/** The SWT-free half of the menu-time capture (design §9.3 [UI turn 1], G2–G3, charset). */
class LineSnapshotCaptureTest : DescribeSpec({
  val file = File("/work/tree/src/A.kt")

  fun facts(
    filePath: File? = file,
    dirty: Boolean = false,
    zeroBasedLine: Int = 4,
    documentText: String = "a\nb\nc\nd\ne\nf\n",
    numberOfLines: Int = 7,
    charsetName: String? = "UTF-8",
  ) = EditorLineFacts(filePath, dirty, zeroBasedLine, documentText, numberOfLines, charsetName)

  fun refusedMessage(result: CaptureResult): String = result.shouldBeInstanceOf<CaptureResult.Refused>().message

  fun snapshotOf(result: CaptureResult): LineSnapshot = result.shouldBeInstanceOf<CaptureResult.Captured>().snapshot

  describe("oneBasedLine") {
    it("the first document line (0) becomes line 1") {
      snapshotOf(LineSnapshotCapture.capture(facts(zeroBasedLine = 0))).oneBasedLine shouldBe 1
    }

    it("any other line is shifted by exactly one") {
      snapshotOf(LineSnapshotCapture.capture(facts(zeroBasedLine = 4))).oneBasedLine shouldBe 5
    }

    it("an unknown line (-1: outside the ruler, no text selection) is refused") {
      refusedMessage(LineSnapshotCapture.capture(facts(zeroBasedLine = -1))) shouldBe
        LineSnapshotCapture.NO_LINE_MESSAGE
    }

    it("oneBasedLineOf maps 0 to 1 and a negative line to null") {
      LineSnapshotCapture.oneBasedLineOf(0) shouldBe 1
      LineSnapshotCapture.oneBasedLineOf(9) shouldBe 10
      LineSnapshotCapture.oneBasedLineOf(-1).shouldBeNull()
    }
  }

  describe("lineCount") {
    it("an empty document has 0 lines although IDocument reports 1") {
      val snapshot =
        snapshotOf(LineSnapshotCapture.capture(facts(zeroBasedLine = 0, documentText = "", numberOfLines = 1)))

      snapshot.lineCount shouldBe 0
      snapshot.oneBasedLine shouldBe 1
      snapshot.documentText shouldBe ""
    }

    it("a non-empty document keeps the document's line count") {
      snapshotOf(LineSnapshotCapture.capture(facts(documentText = "x", numberOfLines = 1))).lineCount shouldBe 1
      snapshotOf(LineSnapshotCapture.capture(facts())).lineCount shouldBe 7
    }
  }

  describe("gates, in order") {
    it("G2: no local file is refused first") {
      refusedMessage(
        LineSnapshotCapture.capture(facts(filePath = null, dirty = true, zeroBasedLine = -1, charsetName = null)),
      ) shouldBe LineSnapshotCapture.NOT_LOCAL_FILE_MESSAGE
    }

    it("G3: a dirty editor is refused with the save-first text, before the line and the charset") {
      refusedMessage(
        LineSnapshotCapture.capture(facts(dirty = true, zeroBasedLine = -1, charsetName = null)),
      ) shouldBe LineCommentAttempt.SAVE_FIRST_MESSAGE
    }

    it("the line is checked before the charset") {
      refusedMessage(
        LineSnapshotCapture.capture(facts(zeroBasedLine = -1, charsetName = null)),
      ) shouldBe LineSnapshotCapture.NO_LINE_MESSAGE
    }
  }

  describe("charset") {
    it("a missing charset is refused") {
      refusedMessage(LineSnapshotCapture.capture(facts(charsetName = null))) shouldBe
        LineSnapshotCapture.NO_CHARSET_MESSAGE
    }

    it("a blank, an illegal or an unsupported charset name is refused") {
      listOf("", "  ", "not a charset!", "x-no-such-charset").forEach { name ->
        refusedMessage(LineSnapshotCapture.capture(facts(charsetName = name))) shouldBe
          LineSnapshotCapture.NO_CHARSET_MESSAGE
      }
    }

    it("a supported name resolves to that charset") {
      snapshotOf(LineSnapshotCapture.capture(facts(charsetName = "ISO-8859-1"))).charset shouldBe
        StandardCharsets.ISO_8859_1
      LineSnapshotCapture.charsetOf("utf-8") shouldBe StandardCharsets.UTF_8
      LineSnapshotCapture.charsetOf(null).shouldBeNull()
    }
  }

  it("a captured snapshot carries the file, the text and the charset unchanged") {
    val snapshot = snapshotOf(LineSnapshotCapture.capture(facts()))

    snapshot shouldBe LineSnapshot(file, 5, 7, "a\nb\nc\nd\ne\nf\n", StandardCharsets.UTF_8)
  }
})
