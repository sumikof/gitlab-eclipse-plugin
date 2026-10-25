package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

private val red = AnsiStyle(fg = AnsiColor.Named(1))
private const val TS = "2024-01-01T00:00:00.000000Z"
private const val OMITTED = 123L
private val HEADER = OMITTED_HEADER + "\n"

private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

private class Assembled(val text: String, val runs: List<StyleRun>, val folds: List<FoldRegion>, val state: RenderState)

/** Renders the first chunk with [renderInitial] and applies the rest with [appendRender], like the live loop. */
private fun renderChunked(raw: ByteArray, omitted: Long, cuts: List<Int>): Assembled {
  val bounds = listOf(0) + cuts + raw.size
  val (first, firstState) = renderInitial(raw.copyOfRange(0, bounds[1]), omitted)
  var text = first.text
  var runs = first.runs
  val folds = ArrayList(first.folds)
  var state = firstState
  for (i in 1 until bounds.size - 1) {
    val delta = appendRender(state, raw.copyOfRange(bounds[i], bounds[i + 1]))
    if (omitted > 0) (delta.replaceFrom >= HEADER.length) shouldBe true
    text = text.substring(0, delta.replaceFrom) + delta.text
    runs = runs.appendFrom(delta.replaceFrom, delta.runs)
    folds.addAll(delta.newFolds)
    state = delta.state
  }
  return Assembled(text, runs.toList(), folds, state)
}

private fun checkSame(raw: ByteArray, omitted: Long, cuts: List<Int>) {
  val (whole, wholeState) = renderInitial(raw, omitted)
  val chunked = renderChunked(raw, omitted, cuts)
  chunked.text shouldBe whole.text
  chunked.runs shouldBe whole.runs.toList()
  chunked.folds shouldBe whole.folds
  chunked.state shouldBe wholeState
}

private fun timestamped(): String {
  val sb = StringBuilder()
  sb.append("$TS 00O first line\n")
  sb.append("$TS 00O+ continued\n")
  sb.append("$TS 01E \u001B[31merror\u001B[0m\n")
  sb.append("$TS 00O section_start:1:s[collapsed=true]\r\u001B[0K\n")
  sb.append("$TS 00O inside 日本\n")
  sb.append("$TS 00O+ more\n")
  sb.append("$TS 00O section_end:1:s\r\u001B[0K\n")
  sb.append("$TS 00O progress 10%\r")
  sb.append("$TS 00O last partial")
  return sb.toString()
}

private val CORPORA = listOf(
  "colored" to bytes("\u001B[32mgreen\u001B[0m text\n\u001B[1;31mbold red\n still red\u001B[0m\nplain\n"),
  "carriage return" to bytes("Downloading 10%\rDownloading 50%\rDone\r\nnext\r\nlast partial"),
  "sections" to bytes(
    "section_start:1:a[collapsed=true]\r\u001B[0Kstart\nbody1\nsection_start:2:b\r\nbody2\n" +
      "section_end:2:b\r\nsection_end:1:a\r\nafter\nsection_end:9:zz\r\nlost\nsection_start:3:open\r\nx",
  ),
  "multibyte" to bytes("日本語のログ\n😀 emoji \u001B[34m青\u001B[0m\nñandú\n"),
  "timestamps" to bytes(timestamped()),
  "invalid utf-8" to byteArrayOf(0x61, 0xFF.toByte(), 0xFE.toByte(), 0x0A, 0x62, 0xE6.toByte(), 0x97.toByte()),
  "no newline at all" to bytes("\u001B[31mjust one long line without any break"),
  "empty lines" to bytes("\n\n\na\n\n"),
)

class JobLogRendererTest : DescribeSpec({
  describe("renderInitial") {
    it("renders colors, sections and CR overwrite") {
      val raw = bytes(
        "section_start:1:a\r\u001B[0Kone\n\u001B[31mred\n\u001B[0mdone\nsection_end:1:a\r\u001B[0K\n",
      )
      val (result, state) = renderInitial(raw, 0)
      result.text shouldBe "one\nred\ndone\n\n"
      result.runs.toList() shouldBe listOf(StyleRun(4, 3, red))
      result.folds shouldBe listOf(FoldRegion(0, 2, false))
      result.omitted shouldBe false
      state.confirmedBytes shouldBe raw.size.toLong()
      state.confirmedChars shouldBe result.text.length
      state.confirmedLines shouldBe 4
      state.pendingRaw.size shouldBe 0
      state.timestamp shouldBe TimestampMode.NONE
    }

    it("renders an empty input as an empty document") {
      val (result, state) = renderInitial(ByteArray(0), 0)
      result.text shouldBe ""
      result.runs.size shouldBe 0
      result.folds shouldBe emptyList()
      state.timestamp shouldBe null
      state.confirmedBytes shouldBe 0L
    }

    it("puts the omitted header first and counts it") {
      val (result, state) = renderInitial(bytes("a\nb\n"), OMITTED)
      result.text shouldBe HEADER + "a\nb\n"
      result.omitted shouldBe true
      state.confirmedBytes shouldBe OMITTED + 4
      state.confirmedChars shouldBe HEADER.length + 4
      state.confirmedLines shouldBe 3
    }

    it("offsets runs and fold lines past the header") {
      val (result, _) = renderInitial(bytes("section_start:1:a\r\n\u001B[31mx\nsection_end:1:a\r\n"), OMITTED)
      result.runs.toList() shouldBe listOf(StyleRun(HEADER.length + 1, 1, red))
      result.folds shouldBe listOf(FoldRegion(1, 2, false))
    }

    it("renders the header alone when nothing is confirmed") {
      val (result, state) = renderInitial(ByteArray(0), OMITTED)
      result.text shouldBe HEADER
      state.confirmedChars shouldBe HEADER.length
      state.confirmedLines shouldBe 1
    }

    it("decodes invalid UTF-8 with replacement characters") {
      val (result, _) = renderInitial(byteArrayOf(0x61, 0xFF.toByte(), 0x0A), 0)
      result.text shouldBe "a�\n"
    }

    it("renders the unfinished tail without confirming it") {
      val (result, state) = renderInitial(bytes("abc\n\u001B[31mdef"), 0)
      result.text shouldBe "abc\ndef"
      result.runs.toList() shouldBe listOf(StyleRun(4, 3, red))
      state.confirmedBytes shouldBe 4L
      state.confirmedChars shouldBe 4
      state.confirmedLines shouldBe 1
      state.style shouldBe AnsiStyle.DEFAULT
      String(state.pendingRaw, Charsets.UTF_8) shouldBe "\u001B[31mdef"
    }

    it("shows only the last progress state of an unfinished tail") {
      renderInitial(bytes("abc\n10%\r50%"), 0).first.text shouldBe "abc\n50%"
    }

    it("limits the initially collapsed sections") {
      val sb = StringBuilder()
      repeat(MAX_INITIALLY_COLLAPSED + 1) {
        sb.append("section_start:1:s$it[collapsed=true]\r\nbody\nsection_end:1:s$it\r\n")
      }
      val (result, state) = renderInitial(bytes(sb.toString()), 0)
      result.folds.size shouldBe MAX_INITIALLY_COLLAPSED + 1
      result.folds.count { it.collapsedInitially } shouldBe MAX_INITIALLY_COLLAPSED
      result.folds.last().collapsedInitially shouldBe false
      state.collapsedFolds shouldBe MAX_INITIALLY_COLLAPSED
    }

    it("carries the SGR style across lines") {
      val (result, state) = renderInitial(bytes("\u001B[31ma\nb\n"), 0)
      result.runs.toList() shouldBe listOf(StyleRun(0, 1, red), StyleRun(2, 1, red))
      state.style shouldBe red
    }

    it("keeps an unterminated section open in the state") {
      val (result, state) = renderInitial(bytes("section_start:1:a\r\nx\n"), 0)
      result.folds shouldBe emptyList()
      state.sections.open.size shouldBe 1
    }
  }

  describe("timestamped traces") {
    val raw = bytes("$TS 00O hello\n$TS 00O+ world\n$TS 00O next\n")

    it("strips headers, joins continuations and holds back the last line group") {
      val (result, state) = renderInitial(raw, 0)
      result.text shouldBe "hello world\nnext\n"
      state.timestamp shouldBe TimestampMode.TIMESTAMPED
      state.confirmedChars shouldBe "hello world\n".length
      state.confirmedLines shouldBe 1
      state.confirmedBytes shouldBe bytes("$TS 00O hello\n$TS 00O+ world\n").size.toLong()
    }

    it("confirms a group once the next group has started") {
      val (_, state) = renderInitial(raw, 0)
      val delta = appendRender(state, bytes("$TS 00O after\n"))
      delta.replaceFrom shouldBe "hello world\n".length
      delta.text shouldBe "next\nafter\n"
      delta.state.confirmedLines shouldBe 2
    }

    it("lets a continuation extend the held back group") {
      val (_, state) = renderInitial(raw, 0)
      val delta = appendRender(state, bytes("$TS 00O+ and more\n"))
      delta.text shouldBe "next and more\n"
    }

    it("treats a first line without a timestamp as plain text") {
      val (result, state) = renderInitial(bytes("plain\nsecond\n"), 0)
      result.text shouldBe "plain\nsecond\n"
      state.timestamp shouldBe TimestampMode.NONE
    }
  }

  describe("appendRender") {
    it("appends complete lines after the confirmed part") {
      val (_, state) = renderInitial(bytes("one\ntw"), 0)
      val delta = appendRender(state, bytes("o\nthree"))
      delta.replaceFrom shouldBe 4
      delta.text shouldBe "two\nthree"
      delta.state.confirmedBytes shouldBe 8L
    }

    it("replaces the unfinished tail on each append") {
      val (_, state) = renderInitial(bytes("a\n10%"), 0)
      val delta = appendRender(state, bytes("\r20%"))
      delta.replaceFrom shouldBe 2
      delta.text shouldBe "20%"
    }

    it("accepts an empty append") {
      val (result, state) = renderInitial(bytes("a\nb"), 0)
      val delta = appendRender(state, ByteArray(0))
      delta.replaceFrom shouldBe 2
      delta.text shouldBe "b"
      (result.text.substring(0, delta.replaceFrom) + delta.text) shouldBe result.text
    }

    it("reassembles a multi-byte character split across appends") {
      val full = bytes("日本\n")
      val (_, state) = renderInitial(full.copyOfRange(0, 2), 0)
      val delta = appendRender(state, full.copyOfRange(2, full.size))
      delta.text shouldBe "日本\n"
    }

    it("never replaces the omitted header") {
      val (_, state) = renderInitial(bytes("a"), OMITTED)
      (appendRender(state, bytes("b\n")).replaceFrom >= HEADER.length) shouldBe true
    }
  }

  describe("timestamped continuation lines") {
    it("joins a continuation after a CRLF line without losing the first part") {
      val raw = bytes("$TS 00O a\r\n$TS 00O+ b\r\n$TS 00O c\n")
      renderInitial(raw, 0L).first.text shouldBe "a b\nc\n"
    }

    it("keeps a continuation line that opens the log") {
      val raw = bytes("$TS 00O+ a\n$TS 00O b\n$TS 00O c\n")
      renderInitial(raw, 0L).first.text shouldBe " a\nb\nc\n"
    }

    it("groups a huge run of continuation lines in linear time") {
      val line = "$TS 00O+ " + "x".repeat(56) + "\n"
      val raw = bytes("$TS 00O start\n" + line.repeat(100_000))
      val started = System.nanoTime()
      val text = renderInitial(raw, 0L).first.text
      val elapsedMs = (System.nanoTime() - started) / 1_000_000
      text.count { it == 'x' } shouldBe 100_000 * 56
      text.count { it == '\n' } shouldBe 1
      (elapsedMs < 4_000) shouldBe true
    }
  }

  describe("incremental rendering equals one-shot rendering") {
    for ((name, raw) in CORPORA) {
      it("holds for every single split point of $name, with and without an omitted header") {
        for (omitted in listOf(0L, OMITTED)) {
          for (cut in 0..raw.size) checkSame(raw, omitted, listOf(cut))
        }
      }

      it("holds for random multi-way splits of $name (fixed seed)") {
        val random = Random(20261023)
        repeat(200) { round ->
          val count = random.nextInt(2, 8)
          val cuts = List(count) { random.nextInt(0, raw.size + 1) }.sorted()
          checkSame(raw, if (round % 2 == 0) 0L else OMITTED, cuts)
        }
      }

      it("holds when $name is fed one byte at a time") {
        checkSame(raw, 0L, (1 until raw.size).toList())
      }
    }
  }
})
