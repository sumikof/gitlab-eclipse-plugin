package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.api.model.GitLabJob
import com.google.gson.JsonSyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

private const val HARD_LIMIT = 8L

private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

private fun body(text: String, etag: String?, total: Long, omitted: Long) =
  TraceFetch.Body(bytes(text), etag, total, omitted)

private fun cursorOf(text: String) = cursorAfter(body(text, null, text.length.toLong(), 0), 0)

private fun stateAfter(text: String): RenderState = renderInitial(bytes(text), 0).second

private fun snap(etag: String?, cursor: TraceCursor?, state: RenderState? = null) =
  TraceSnapshot(etag, cursor, state, StyleRunIndex.EMPTY)

private fun show(result: TraceStepResult): String = when (result) {
  is TraceStepResult.Unchanged -> "Unchanged(etag=${result.etag},total=${result.totalBytes})"
  is TraceStepResult.Failed -> "Failed(${result.failure.kind},${result.failure.outcome})"
  is TraceStepResult.Changed -> when (val update = result.update) {
    is JobLogUpdate.Append -> "Append(from=${update.replaceFrom},text=${update.text})"
    is JobLogUpdate.Full -> "Full(omitted=${update.result.omitted})"
  }
}

private class StepRow(val name: String, val snapshot: TraceSnapshot, val reply: TraceFetch, val expected: String, val hardLimit: Long? = null)

private val ROWS = listOf(
  StepRow("first poll renders from scratch", snap(null, null), body("a\nb", "\"1\"", 3, 0), "Full(omitted=false)"),
  StepRow(
    "304 keeps the etag",
    snap("W/\"1\"", cursorOf("a\nb")),
    TraceFetch.NotModified,
    "Unchanged(etag=W/\"1\",total=3)"
  ),
  StepRow(
    "304 without etag is a failure",
    snap(null, cursorOf("a\nb")),
    TraceFetch.NotModified,
    "Failed(TRANSIENT,notModified)"
  ),
  StepRow(
    "same bytes without etag",
    snap(null, cursorOf("a\nb")),
    body("a\nb", null, 3, 0),
    "Unchanged(etag=null,total=3)"
  ),
  StepRow(
    "append replaces the unfinished tail",
    snap("\"1\"", cursorOf("a\nb"), stateAfter("a\nb")),
    body("a\nbc\n", "\"2\"", 5, 0),
    "Append(from=2,text=bc\n)",
  ),
  StepRow(
    "mismatch falls back to full",
    snap("\"1\"", cursorOf("a\nb"), stateAfter("a\nb")),
    body("X\nbc\n", "\"2\"", 5, 0),
    "Full(omitted=false)",
  ),
  StepRow(
    "hard limit falls back to the tail",
    snap("\"1\"", cursorOf("a\nb"), stateAfter("a\nb")),
    body("bcde\n", "\"2\"", 7, 2),
    "Full(omitted=true)",
    hardLimit = 4,
  ),
  StepRow(
    "broken invariant falls back to full",
    snap("\"1\"", cursorOf("a\nb"), stateAfter("a")),
    body("a\nbc\n", "\"2\"", 5, 0),
    "Full(omitted=false)",
  ),
  StepRow(
    "missing state falls back to full",
    snap("\"1\"", cursorOf("a\nb"), null),
    body("a\nbc\n", "\"2\"", 5, 0),
    "Full(omitted=false)",
  ),
)

private data class FailureRow(val name: String, val error: Exception, val expected: PollFailure?)

private val FAILURE_ROWS = listOf(
  FailureRow("cancellation", CancellationException("x"), null),
  FailureRow("404", GitLabApiException(404, "body", "c1"), PollFailure(PollFailureKind.NOT_FOUND, "http", 404, "c1")),
  FailureRow("401", GitLabApiException(401, "body", null), PollFailure(PollFailureKind.DENIED, "http", 401, null)),
  FailureRow("403", GitLabApiException(403, "body", "c3"), PollFailure(PollFailureKind.DENIED, "http", 403, "c3")),
  FailureRow("429", GitLabApiException(429, "", null), PollFailure(PollFailureKind.TRANSIENT, "http", 429, null)),
  FailureRow("503", GitLabApiException(503, "", "c5"), PollFailure(PollFailureKind.TRANSIENT, "http", 503, "c5")),
  FailureRow("400", GitLabApiException(400, "", null), PollFailure(PollFailureKind.TRANSIENT, "http", 400, null)),
  FailureRow("timeout", HttpTimeoutException("slow"), PollFailure(PollFailureKind.TRANSIENT, "timeout")),
  FailureRow("io", IOException("https://secret.example/x"), PollFailure(PollFailureKind.TRANSIENT, "io")),
  FailureRow(
    "parse",
    JsonSyntaxException("bad"),
    PollFailure(PollFailureKind.TRANSIENT, "parse", exceptionClass = "JsonSyntaxException"),
  ),
  FailureRow(
    "unexpected",
    IllegalStateException("boom"),
    PollFailure(PollFailureKind.TRANSIENT, "unexpected", exceptionClass = "IllegalStateException"),
  ),
)

private val CORPORA = listOf(
  "colored" to "\u001B[32mgreen\u001B[0m text\n\u001B[1;31mbold red\n still red\u001B[0m\nplain\n",
  "sections" to "section_start:1:a[collapsed=true]\r\u001B[0Kstart\nbody1\nsection_end:1:a\r\nafter\nlast partial",
  "multibyte" to "日本語のログ\n😀 emoji \u001B[34m青\u001B[0m\nñandú\n",
  "timestamps" to "2024-01-01T00:00:00.000000Z 00O first\n2024-01-01T00:00:00.000000Z 00O+ more\n" +
    "2024-01-01T00:00:00.000000Z 01E err\n2024-01-01T00:00:00.000000Z 00O tail",
)

class JobLogPollStepTest : DescribeSpec({
  describe("traceStep") {
    withData(nameFn = { it.name }, ROWS) { row ->
      var sent: String? = "not called"
      val result = traceStep(row.snapshot, { etag ->
        sent = etag
        row.reply
      }, row.hardLimit ?: HARD_DISPLAY_RAW_BYTES)

      show(result) shouldBe row.expected
      sent shouldBe row.snapshot.etag
    }

    it("takes the next etag from the answer, null included") {
      val snapshot = snap("\"1\"", cursorOf("a\nb"), stateAfter("a\nb"))

      val withEtag = traceStep(snapshot, { body("a\nbc\n", "W/\"2\"", 5, 0) })
      val without = traceStep(snapshot, { body("a\nbc\n", null, 5, 0) })

      withEtag.shouldBeInstanceOf<TraceStepResult.Changed>().etag shouldBe "W/\"2\""
      without.shouldBeInstanceOf<TraceStepResult.Changed>().etag shouldBe null
    }

    it("returns the whole new run index, equal to appendFrom on the old one") {
      val state = stateAfter("\u001B[31mred\nmore")
      val snapshot = TraceSnapshot(
        "\"1\"",
        cursorOf("\u001B[31mred\nmore"),
        state,
        renderInitial(bytes("\u001B[31mred\nmore"), 0).first.runs
      )
      val grown = "\u001B[31mred\nmore text\n"

      val changed = traceStep(
        snapshot,
        { body(grown, "\"2\"", grown.length.toLong(), 0) }
      ).shouldBeInstanceOf<TraceStepResult.Changed>()

      val update = changed.update.shouldBeInstanceOf<JobLogUpdate.Append>()
      val expected = snapshot.runs.appendFrom(update.replaceFrom, appendRender(state, bytes(" text\n")).runs)
      update.runs shouldBe expected
    }

    it("takes the new etag from an unchanged body too") {
      val snapshot = snap("\"1\"", cursorOf("a\nb"))

      val result = traceStep(snapshot, { body("a\nb", "\"2\"", 3, 0) })

      show(result) shouldBe "Unchanged(etag=\"2\",total=3)"
    }

    it("takes the etag from the answer on a full render as well") {
      val first = traceStep(snap(null, null), { body("a\nb", "W/\"9\"", 3, 0) })
      first.shouldBeInstanceOf<TraceStepResult.Changed>().etag shouldBe "W/\"9\""

      val snapshot = snap("\"1\"", cursorOf("a\nb"), stateAfter("a\nb"))
      val mismatch = traceStep(snapshot, { body("X\nbc\n", "\"2\"", 5, 0) })
      mismatch.shouldBeInstanceOf<TraceStepResult.Changed>().etag shouldBe "\"2\""
      mismatch.update.shouldBeInstanceOf<JobLogUpdate.Full>()
    }

    it("keeps the display start across appends and takes the omitted bytes on a full render") {
      // Raw offsets: the first fetch cut 100 bytes off; the server window then moves on to 102 (omitted differs).
      val first = traceStep(snap(null, null), { body("x\nab", "e1", 104, 100) }, HARD_LIMIT)
        .shouldBeInstanceOf<TraceStepResult.Changed>()
      first.update.shouldBeInstanceOf<JobLogUpdate.Full>()
      first.cursor.displayStartBytes shouldBe 100

      val second = traceStep(
        TraceSnapshot("e1", first.cursor, first.state, (first.update as JobLogUpdate.Full).result.runs),
        { body("abcde", "e2", 107, 102) },
        HARD_LIMIT,
      ).shouldBeInstanceOf<TraceStepResult.Changed>()
      val append = second.update.shouldBeInstanceOf<JobLogUpdate.Append>()
      second.cursor.displayStartBytes shouldBe 100 // kept from the first render, not the body's omitted bytes (102)

      val third = traceStep(
        TraceSnapshot("e2", second.cursor, second.state, append.runs),
        { body("abcdefg", "e3", 109, 102) },
        HARD_LIMIT,
      )
      // 109 - 100 = 9 raw bytes shown since the display start, over the limit of 8: rebuild from the tail.
      show(third) shouldBe "Full(omitted=true)"
      third.shouldBeInstanceOf<TraceStepResult.Changed>().cursor.displayStartBytes shouldBe 102
    }

    it("does not hit the hard limit while the bytes shown since the display start stay within it") {
      val first = traceStep(snap(null, null), { body("x\nab", "e1", 104, 100) }, HARD_LIMIT)
        .shouldBeInstanceOf<TraceStepResult.Changed>()

      val second = traceStep(
        TraceSnapshot("e1", first.cursor, first.state, (first.update as JobLogUpdate.Full).result.runs),
        { body("abcdef", "e2", 108, 102) },
        HARD_LIMIT,
      )

      // 108 - 100 = 8 raw bytes, exactly the limit: still an append (the unfinished "ab" is re-rendered with "cdef").
      second.shouldBeInstanceOf<TraceStepResult.Changed>().update.shouldBeInstanceOf<JobLogUpdate.Append>()
        .text shouldBe "abcdef"
    }

    it("rethrows cancellation and turns other errors into failures") {
      val snapshot = snap(null, null)
      shouldThrow<CancellationException> { traceStep(snapshot, { throw CancellationException("stop") }) }
      show(traceStep(snapshot, { throw GitLabApiException(404, "", null) })) shouldBe "Failed(NOT_FOUND,http)"
    }

    for ((name, text) in CORPORA) {
      it("applying polls of growing prefixes of $name ends with the one-shot render") {
        val raw = bytes(text)
        val random = Random(20261025)
        repeat(60) {
          val cuts = List(random.nextInt(1, 8)) { random.nextInt(0, raw.size + 1) }.sorted() + raw.size
          var cursor: TraceCursor? = null
          var state: RenderState? = null
          var doc = ""
          var runs = StyleRunIndex.EMPTY
          val folds = ArrayList<FoldRegion>()
          cuts.forEachIndexed { i, cut ->
            val prefix = raw.copyOfRange(0, cut)
            val etag = if (i == 0) null else "e$i"
            val result = traceStep(
              TraceSnapshot(etag, cursor, state, runs),
              { TraceFetch.Body(prefix, "e${i + 1}", cut.toLong(), 0) }
            )
            if (result is TraceStepResult.Changed) {
              cursor = result.cursor
              state = result.state
              when (val update = result.update) {
                is JobLogUpdate.Append -> {
                  doc = doc.substring(0, update.replaceFrom) + update.text
                  runs = update.runs
                  folds.addAll(update.newFolds)
                }
                is JobLogUpdate.Full -> {
                  doc = update.result.text
                  runs = update.result.runs
                  folds.clear()
                  folds.addAll(update.result.folds)
                }
              }
            }
          }
          val (whole, wholeState) = renderInitial(raw, 0)
          doc shouldBe whole.text
          runs.toList() shouldBe whole.runs.toList()
          folds shouldBe whole.folds
          state shouldBe wholeState
        }
      }
    }
  }

  describe("statusStep") {
    it("reports a started job with a trace") {
      val result = statusStep { GitLabJob(1, status = "running", startedAt = "2024-01-01T00:00:00Z") }
      result shouldBe StatusStepResult.Seen("running", hasTrace = true, erased = false)
    }

    it("reports a job that has not started") {
      statusStep {
        GitLabJob(1, status = "pending")
      } shouldBe StatusStepResult.Seen("pending", hasTrace = false, erased = false)
    }

    it("reports an erased job") {
      val job = GitLabJob(1, status = "success", startedAt = "a", erasedAt = "b")
      statusStep { job } shouldBe StatusStepResult.Seen("success", hasTrace = false, erased = true)
    }

    it("classifies a 404") {
      val result = statusStep { throw GitLabApiException(404, "", "c") }
      result shouldBe StatusStepResult.Failed(PollFailure(PollFailureKind.NOT_FOUND, "http", 404, "c"))
    }

    it("rethrows cancellation") {
      shouldThrow<CancellationException> { statusStep { throw CancellationException("stop") } }
    }
  }

  describe("classifyPollFailure") {
    withData(nameFn = { it.name }, FAILURE_ROWS) { row ->
      classifyPollFailure(row.error) shouldBe row.expected
    }
  }
})
