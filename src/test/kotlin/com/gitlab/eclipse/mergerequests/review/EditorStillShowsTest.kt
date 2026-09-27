package com.gitlab.eclipse.mergerequests.review

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EditorStillShowsTest : DescribeSpec({
  val input = Any()
  val document = Any()

  describe("editorStillShows") {
    it("restarts when the live editor still shows the same input and document") {
      editorStillShows(true, input, document, input, document) shouldBe true
    }

    it("accepts an equal (not identical) input when the document instance is the same") {
      editorStillShows(true, "file-a", document, String("file-a".toCharArray()), document) shouldBe true
    }

    it("does not restart after the input changed") {
      editorStillShows(true, input, document, Any(), document) shouldBe false
    }

    it("does not restart when the editor now has a different document instance") {
      editorStillShows(true, input, document, input, Any()) shouldBe false
    }

    it("does not restart when the editor is gone") {
      editorStillShows(false, input, document, input, document) shouldBe false
    }

    it("does not restart when nothing was captured at open time") {
      editorStillShows(true, null, null, null, null) shouldBe false
      editorStillShows(true, input, null, input, null) shouldBe false
    }
  }
})
