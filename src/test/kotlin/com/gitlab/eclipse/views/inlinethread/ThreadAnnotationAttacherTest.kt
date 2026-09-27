package com.gitlab.eclipse.views.inlinethread

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.source.AnnotationModel

private const val TYPE = "test.thread"

/** A ten-line document connected to a parent model, the way an open editor's model is (E3 / E4). */
private class AttachedDocument {
  val document = Document((1..10).joinToString("\n") { "line $it" })
  val parent = AnnotationModel().also { it.connect(document) }
  val attacher = ThreadAnnotationAttacher()

  fun show(vararg annotations: LineAnnotation) {
    attacher.replace(document, parent, annotations.toList()) shouldBe true
  }

  /** Inserts [count] lines at the very start of the document, moving every annotation down. */
  fun insertLinesAtTop(count: Int) {
    document.replace(0, 0, "new\n".repeat(count))
  }
}

class ThreadAnnotationAttacherTest : DescribeSpec({
  describe("ThreadAnnotationAttacher.threadIdsAt (live positions, E4)") {

    it("returns the ids of the annotation on the line, including line 1") {
      val doc = AttachedDocument()
      doc.show(LineAnnotation(1, TYPE, "a", listOf("A")), LineAnnotation(5, TYPE, "b", listOf("B")))

      doc.attacher.threadIdsAt(doc.document, 1) shouldContainExactly listOf("A")
      doc.attacher.threadIdsAt(doc.document, 5) shouldContainExactly listOf("B")
      doc.attacher.threadIdsAt(doc.document, 2).shouldBeEmpty()
    }

    it("follows an edit: the moved annotation's ids are found on its new line, not the other thread's") {
      val doc = AttachedDocument()
      // A loaded on line 3, B loaded on line 5; two lines inserted above both.
      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")), LineAnnotation(5, TYPE, "b", listOf("B")))

      doc.insertLinesAtTop(2)

      doc.attacher.threadIdsAt(doc.document, 5) shouldContainExactly listOf("A")
      doc.attacher.threadIdsAt(doc.document, 7) shouldContainExactly listOf("B")
      doc.attacher.threadIdsAt(doc.document, 3).shouldBeEmpty()
      doc.attacher.hasAnnotationAt(doc.document, 5) shouldBe true
      doc.attacher.hasAnnotationAt(doc.document, 3) shouldBe false
    }

    it("collects the ids of every annotation on the line, in the order they were shown") {
      val doc = AttachedDocument()
      doc.show(
        LineAnnotation(4, TYPE, "x", listOf("X")),
        LineAnnotation(2, TYPE, "y", listOf("Y")),
        LineAnnotation(4, TYPE, "z", listOf("Z")),
      )

      doc.attacher.threadIdsAt(doc.document, 4) shouldContainExactly listOf("X", "Z")
    }

    it("forgets the ids of a replaced set and of a detached document") {
      val doc = AttachedDocument()
      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")))
      doc.show(LineAnnotation(3, TYPE, "a2", listOf("A2")))

      doc.attacher.threadIdsAt(doc.document, 3) shouldContainExactly listOf("A2")

      doc.attacher.detach(doc.document)

      doc.attacher.threadIdsAt(doc.document, 3).shouldBeEmpty()
    }
  }

  describe("ThreadAnnotationAttacher.replace keeps the live line of a known thread (Codex r4)") {

    it("a refresh with the server's line keeps a moved thread where the edit put it") {
      val doc = AttachedDocument()
      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")))
      doc.insertLinesAtTop(2)
      doc.attacher.threadIdsAt(doc.document, 5) shouldContainExactly listOf("A")

      doc.show(LineAnnotation(3, TYPE, "a refreshed", listOf("A")))

      doc.attacher.threadIdsAt(doc.document, 5) shouldContainExactly listOf("A")
      doc.attacher.threadIdsAt(doc.document, 3).shouldBeEmpty()
    }

    it("a brand-new thread id is placed on the server's line, next to a kept one") {
      val doc = AttachedDocument()
      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")))
      doc.insertLinesAtTop(2)

      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")), LineAnnotation(3, TYPE, "n", listOf("N")))

      doc.attacher.threadIdsAt(doc.document, 5) shouldContainExactly listOf("A")
      doc.attacher.threadIdsAt(doc.document, 3) shouldContainExactly listOf("N")
    }

    it("an annotation sharing one id with a moved one follows that live line") {
      val doc = AttachedDocument()
      doc.show(LineAnnotation(4, TYPE, "a", listOf("A")))
      doc.insertLinesAtTop(1)

      doc.show(LineAnnotation(4, TYPE, "ab", listOf("B", "A")))

      doc.attacher.threadIdsAt(doc.document, 5) shouldContainExactly listOf("B", "A")
    }

    it("after a detach the server's line is used again") {
      val doc = AttachedDocument()
      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")))
      doc.insertLinesAtTop(2)
      doc.attacher.detach(doc.document)

      doc.show(LineAnnotation(3, TYPE, "a", listOf("A")))

      doc.attacher.threadIdsAt(doc.document, 3) shouldContainExactly listOf("A")
    }
  }
})
