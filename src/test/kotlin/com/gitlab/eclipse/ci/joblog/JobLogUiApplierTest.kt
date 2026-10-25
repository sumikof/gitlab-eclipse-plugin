package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.IDocumentListener

private data class TailCase(val text: String, val bottomEnd: Int, val expected: Boolean)

private val TAIL_CASES = listOf(
  TailCase("", 0, true),
  TailCase("a\nb\nc", 5, true),
  TailCase("a\nb\nc", 3, false),
  TailCase("a\nb\n", 3, true),
  TailCase("a\nb\n", 1, false),
  TailCase("abc", 2, true),
  // The last line is one character: the bottom line's end offset is exactly its start.
  TailCase("a\nb", 2, true),
  TailCase("a\nb", 1, false),
)

/** Records every call, in order and by name, into [log]; the document changes are recorded too. */
private class RecordingSurface(
  private val name: String,
  override val document: IDocument,
  private val log: MutableList<String>,
  var tail: Boolean = true,
) : JobLogTextSurface {
  var assignedTop: Int? = null

  override var topIndex: Int = 0
    set(value) {
      field = value
      assignedTop = value
    }

  override fun isTailVisible(): Boolean = tail.also { log += "$name:isTail" }

  override fun swapRuns(runs: StyleRunIndex, from: Int) {
    log += "$name:swapRuns:$from"
  }

  override fun refreshVisible() {
    log += "$name:refresh"
  }

  override fun addFolds(folds: List<FoldRegion>) {
    log += "$name:addFolds:${folds.size}"
  }

  override fun applyRendered(result: RenderResult) {
    log += "$name:applyRendered"
  }

  override fun revealEnd() {
    log += "$name:reveal"
  }
}

private fun recordChanges(document: IDocument, log: MutableList<String>) {
  document.addDocumentListener(object : IDocumentListener {
    override fun documentAboutToBeChanged(event: DocumentEvent) = Unit

    override fun documentChanged(event: DocumentEvent) {
      log += "replace"
    }
  })
}

private fun append(from: Int, text: String, folds: List<FoldRegion> = emptyList()) =
  JobLogUpdate.Append(from, text, StyleRunIndex.EMPTY, folds)

private fun full(text: String) =
  JobLogUpdate.Full(RenderResult(text, StyleRunIndex.EMPTY, emptyList(), omitted = false))

/**
 * [JobLogUiApplier] and [isTailVisible] (Task 11, R-B2-7, R2-4) with a real JFace [Document] and
 * recording surfaces. The viewer side ([JobLogPresenter.swapRuns], the real scrolling) is manual B2-1 / B2-2 / B2-11.
 */
class JobLogUiApplierTest : DescribeSpec({
  describe("isTailVisible") {
    val name = { c: TailCase -> "\"${c.text.replace("\n", "\\n")}\" bottom=${c.bottomEnd} -> ${c.expected}" }
    withData(nameFn = name, TAIL_CASES) { c ->
      isTailVisible(c.bottomEnd, Document(c.text)) shouldBe c.expected
    }
  }

  describe("Append") {
    it("asks for the tail and swaps the runs before the replace, then folds, refreshes and reveals") {
      val log = mutableListOf<String>()
      val document = Document("a\nb").also { recordChanges(it, log) }
      val s = RecordingSurface("s", document, log)

      JobLogUiApplier.apply(append(2, "bc\nd"), listOf(s), followOnFull = false) shouldBe true

      log shouldContainExactly listOf("s:isTail", "s:swapRuns:2", "replace", "s:addFolds:0", "s:refresh", "s:reveal")
    }

    it("replaces the unfinished last line") {
      val document = Document("a\nb")

      JobLogUiApplier.apply(append(2, "bc\nd"), listOf(RecordingSurface("s", document, mutableListOf())), false)

      document.get() shouldBe "a\nbc\nd"
    }

    it("changes a shared document once and reveals only the surface whose tail was on screen") {
      val log = mutableListOf<String>()
      val document = Document("a\n").also { recordChanges(it, log) }
      val atEnd = RecordingSurface("end", document, log, tail = true)
      val scrolledUp = RecordingSurface("up", document, log, tail = false)

      JobLogUiApplier.apply(append(2, "b\n"), listOf(atEnd, scrolledUp), followOnFull = false) shouldBe true

      document.get() shouldBe "a\nb\n"
      log.count { it == "replace" } shouldBe 1
      log shouldContainExactly listOf(
        "end:isTail", "up:isTail", "end:swapRuns:2", "up:swapRuns:2", "replace",
        "end:addFolds:0", "end:refresh", "up:addFolds:0", "up:refresh", "end:reveal",
      )
    }

    it("changes each of two documents once") {
      val first = Document("a\n")
      val second = Document("a\n")
      val surfaces = listOf(first, second).mapIndexed { i, d -> RecordingSurface("$i", d, mutableListOf()) }

      JobLogUiApplier.apply(append(2, "b\n"), surfaces, false) shouldBe true

      first.get() shouldBe "a\nb\n"
      second.get() shouldBe "a\nb\n"
    }

    it("passes the new folds, and an empty list when there are none (existing folds stay)") {
      val log = mutableListOf<String>()
      val s = RecordingSurface("s", Document("a\n"), log)

      JobLogUiApplier.apply(append(2, "b\nc\n", listOf(FoldRegion(1, 2, true))), listOf(s), false)
      JobLogUiApplier.apply(append(6, "d\n"), listOf(s), false)

      log.filter { it.startsWith("s:addFolds") } shouldContainExactly listOf("s:addFolds:1", "s:addFolds:0")
    }

    describe("does nothing and returns false when the append does not fit (R2-4)") {
      val header = "$OMITTED_HEADER\n"

      it("inside the omitted-output header") {
        val log = mutableListOf<String>()
        val document = Document(header + "x\n")
        val s = RecordingSurface("s", document, log)

        JobLogUiApplier.apply(append(header.length - 1, "y\n"), listOf(s), false) shouldBe false

        document.get() shouldBe header + "x\n"
        log shouldNotContain "s:swapRuns:${header.length - 1}"
      }

      it("past the end of the document") {
        val document = Document("a\n")
        val s = RecordingSurface("s", document, mutableListOf())

        JobLogUiApplier.apply(append(3, "b\n"), listOf(s), false) shouldBe false

        document.get() shouldBe "a\n"
      }

      it("when one of two documents does not fit, neither changes") {
        val fits = Document("a\n")
        val short = Document("")
        val surfaces = listOf(fits, short).mapIndexed { i, d -> RecordingSurface("$i", d, mutableListOf()) }

        JobLogUiApplier.apply(append(2, "b\n"), surfaces, false) shouldBe false

        fits.get() shouldBe "a\n"
        short.get() shouldBe ""
      }
    }

    it("right after the header and at the very end it fits") {
      val header = "$OMITTED_HEADER\n"
      val document = Document(header)
      val s = RecordingSurface("s", document, mutableListOf())

      JobLogUiApplier.apply(append(header.length, "x\n"), listOf(s), false) shouldBe true
      JobLogUiApplier.apply(append(document.length, "y\n"), listOf(s), false) shouldBe true

      document.get() shouldBe header + "x\ny\n"
    }
  }

  describe("Full") {
    it("sets the text, then decorates, then reveals when followOnFull") {
      val log = mutableListOf<String>()
      val document = Document().also { recordChanges(it, log) }
      val s = RecordingSurface("s", document, log)

      JobLogUiApplier.apply(full("a\nb\n"), listOf(s), followOnFull = true) shouldBe true

      document.get() shouldBe "a\nb\n"
      log shouldContainExactly listOf("replace", "s:applyRendered", "s:reveal")
    }

    it("a new log on an empty document opens at the top unless followOnFull") {
      val log = mutableListOf<String>()
      val s = RecordingSurface("s", Document(), log, tail = true)

      JobLogUiApplier.apply(full("a\nb\n"), listOf(s), followOnFull = false)

      log shouldNotContain "s:reveal"
      s.assignedTop shouldBe 0
    }

    it("a surface at the end of the old text follows to the end of the new one") {
      val log = mutableListOf<String>()
      val s = RecordingSurface("s", Document("old\n"), log, tail = true)

      JobLogUiApplier.apply(full("a\nb\n"), listOf(s), followOnFull = false)

      log.last() shouldBe "s:reveal"
    }

    it("a surface scrolled up keeps its top line, as far as the new text allows") {
      val keeps = RecordingSurface("keeps", Document("1\n2\n3\n4\n5\n"), mutableListOf(), tail = false).apply {
        topIndex = 2
        assignedTop = null
      }
      val clamps = RecordingSurface("clamps", Document("1\n2\n3\n4\n5\n"), mutableListOf(), tail = false).apply {
        topIndex = 4
        assignedTop = null
      }

      JobLogUiApplier.apply(full("a\nb\nc\nd\n"), listOf(keeps), followOnFull = false)
      JobLogUiApplier.apply(full("a\nb\n"), listOf(clamps), followOnFull = false)

      keeps.assignedTop shouldBe 2
      clamps.assignedTop shouldBe 2
    }

    it("leaves a document that already holds the text untouched") {
      val log = mutableListOf<String>()
      val document = Document("a\n").also { recordChanges(it, log) }

      JobLogUiApplier.apply(full("a\n"), listOf(RecordingSurface("s", document, log)), followOnFull = false)

      log shouldNotContain "replace"
      log shouldContainExactly listOf("s:isTail", "s:applyRendered", "s:reveal")
    }
  }
})
