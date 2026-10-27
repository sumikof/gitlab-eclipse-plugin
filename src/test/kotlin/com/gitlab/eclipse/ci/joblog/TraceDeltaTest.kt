package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.TraceFetch
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private const val HARD_LIMIT = 7L
private const val WINDOW = 4

private fun body(text: String, total: Long, omitted: Long) =
  TraceFetch.Body(text.toByteArray(Charsets.UTF_8), null, total, omitted)

private fun cursor(total: Long, display: Long, cmpStart: Long, cmp: String) =
  TraceCursor(total, display, cmpStart, cmp.toByteArray(Charsets.UTF_8))

private data class DeltaCase(val name: String, val prev: TraceCursor?, val body: TraceFetch.Body, val expected: String)

private fun show(delta: TraceDelta): String = when (delta) {
  TraceDelta.Unchanged -> "Unchanged"
  is TraceDelta.Appended -> "Appended:" + String(delta.bytes, Charsets.UTF_8)
  is TraceDelta.Full -> "Full:" + delta.reason
}

private val DELTA_CASES = listOf(
  DeltaCase("first fetch", null, body("abc", 3, 0), "Full:INITIAL"),
  DeltaCase("same size same bytes", cursor(6, 0, 2, "cdef"), body("abcdef", 6, 0), "Unchanged"),
  DeltaCase("grew", cursor(6, 2, 2, "cdef"), body("abcdefgh", 8, 0), "Appended:gh"),
  DeltaCase("grew with cut head (overlap partial)", cursor(6, 4, 2, "cdef"), body("efgh", 8, 4), "Appended:gh"),
  DeltaCase("shrunk", cursor(6, 0, 2, "cdef"), body("abc", 3, 0), "Full:SHRUNK"),
  DeltaCase("gap: no overlap", cursor(6, 0, 2, "cdef"), body("ghij", 10, 6), "Full:GAP"),
  DeltaCase("gap: past the end", cursor(6, 0, 2, "cdef"), body("xyz", 12, 9), "Full:GAP"),
  DeltaCase("empty log then cut start", cursor(0, 0, 0, ""), body("xyz", 9, 6), "Full:GAP"),
  DeltaCase("empty log then grew", cursor(0, 0, 0, ""), body("ab", 2, 0), "Appended:ab"),
  DeltaCase("rewritten same size", cursor(6, 0, 2, "cdef"), body("abcdXf", 6, 0), "Full:MISMATCH"),
  DeltaCase("rewritten in the last overlap byte only", cursor(6, 0, 2, "cdef"), body("abcdeX", 6, 0), "Full:MISMATCH"),
  DeltaCase("last overlap byte rewritten, grown", cursor(6, 0, 2, "cdef"), body("abcdeXg", 7, 0), "Full:MISMATCH"),
  DeltaCase("rewritten before growth", cursor(6, 0, 2, "cdef"), body("abXdefgh", 8, 0), "Full:MISMATCH"),
  DeltaCase("hard limit reached", cursor(6, 0, 2, "cdef"), body("abcdefgh", 8, 0), "Full:HARD_LIMIT"),
  DeltaCase("hard limit counts from display start", cursor(6, 4, 2, "cdef"), body("efgh", 8, 4), "Appended:gh"),
  DeltaCase("hard limit not reached (equal)", cursor(6, 0, 2, "cdef"), body("abcdefg", 7, 0), "Appended:g"),
  DeltaCase("unchanged wins over the hard limit", cursor(8, 0, 4, "efgh"), body("abcdefgh", 8, 0), "Unchanged"),
)

class TraceDeltaTest : DescribeSpec({
  describe("traceDelta") {
    withData(nameFn = { it.name }, DELTA_CASES) { case ->
      show(traceDelta(case.prev, case.body, HARD_LIMIT)) shouldBe case.expected
    }

    it("compares bytes, so a multi-byte character straddling the overlap boundary is judged byte by byte") {
      val old = "日本語".toByteArray(Charsets.UTF_8) // 9 bytes
      val prev = TraceCursor(9, 0, 4, old.copyOfRange(4, 9))
      val same = TraceFetch.Body(old + "!".toByteArray(), null, 10, 0)
      show(traceDelta(prev, same)) shouldBe "Appended:!"

      val changed = old.copyOf().also { it[5] = (it[5] + 1).toByte() }
      val rewritten = TraceFetch.Body(changed + "!".toByteArray(), null, 10, 0)
      show(traceDelta(prev, rewritten)) shouldBe "Full:MISMATCH"
    }
  }

  describe("cursorAfter") {
    it("keeps the whole body when it is shorter than the window") {
      val cursor = cursorAfter(body("abc", 3, 0), 0, WINDOW)
      cursor.totalBytes shouldBe 3
      cursor.compareStartBytes shouldBe 0
      String(cursor.compareBytes) shouldBe "abc"
    }

    it("keeps only the last window bytes of a longer body and counts from the absolute end") {
      val cursor = cursorAfter(body("efghij", 20, 14), 14, WINDOW)
      cursor.displayStartBytes shouldBe 14
      cursor.compareStartBytes shouldBe 16
      String(cursor.compareBytes) shouldBe "ghij"
    }

    it("keeps displayStartBytes as given, independent of the body's omitted bytes") {
      val cursor = cursorAfter(body("efghij", 20, 14), 2, WINDOW)
      cursor.displayStartBytes shouldBe 2
      cursor.compareStartBytes shouldBe 16
      cursor.totalBytes shouldBe 20
    }

    it("copies the bytes") {
      val fetched = body("abcdef", 6, 0)
      val cursor = cursorAfter(fetched, 0, WINDOW)
      fetched.bytes[5] = 'X'.code.toByte()
      String(cursor.compareBytes) shouldBe "cdef"
    }
  }
})
