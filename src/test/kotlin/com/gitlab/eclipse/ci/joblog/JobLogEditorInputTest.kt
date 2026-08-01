package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class JobLogEditorInputTest : DescribeSpec({
  val key = JobLogKey.of("https://gitlab.example.com", "fp-a", 10L, 77L)

  describe("equals/hashCode") {
    it("inputs with equal keys are equal even with different content instances and text") {
      val a = JobLogEditorInput(key, JobLogContent("first text"))
      val b = JobLogEditorInput(key, JobLogContent("completely different"))
      (a == b) shouldBe true
      a.hashCode() shouldBe b.hashCode()
    }

    it("different connHash with identical projectId/jobId is NOT equal (AC-8)") {
      val otherConn = JobLogKey.of("https://gitlab.example.com", "fp-b", 10L, 77L)
      otherConn.connHash shouldNotBe key.connHash
      val a = JobLogEditorInput(key, JobLogContent("same"))
      val b = JobLogEditorInput(otherConn, JobLogContent("same"))
      (a == b) shouldBe false
    }
  }

  describe("transient input contract") {
    val input = JobLogEditorInput(key, JobLogContent(""))

    it("exists() is false (kept out of EditorHistory)") {
      input.exists() shouldBe false
    }

    it("is not persistable") {
      input.persistable shouldBe null
    }

    it("name is job-<jobId>.log") {
      input.name shouldBe "job-77.log"
    }
  }

  describe("getStorage") {
    it("reflects the current content.text on a fresh storage read") {
      val content = JobLogContent("before")
      val input = JobLogEditorInput(key, content)
      input.storage.contents.readBytes().toString(Charsets.UTF_8) shouldBe "before"
      content.text = "after refresh"
      input.storage.contents.readBytes().toString(Charsets.UTF_8) shouldBe "after refresh"
    }
  }
})
