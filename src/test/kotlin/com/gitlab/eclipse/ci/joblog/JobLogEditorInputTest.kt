package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

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

  describe("connection fields (design B7)") {
    val url = "https://secret-host.example.com/gitlab"
    val fingerprint = "fp-connection-77"

    it("keep equals/hashCode on the key alone") {
      val withConnection = JobLogEditorInput(key, JobLogContent("a"), url, fingerprint)
      val otherConnection = JobLogEditorInput(key, JobLogContent("b"), "https://other.example", "fp-other")
      val legacy = JobLogEditorInput(key, JobLogContent("c"))
      (withConnection == otherConnection) shouldBe true
      (withConnection == legacy) shouldBe true
      withConnection.hashCode() shouldBe legacy.hashCode()
    }

    it("are readable in the module and default to empty") {
      val input = JobLogEditorInput(key, JobLogContent(""), url, fingerprint)
      input.instanceUrl shouldBe url
      input.authFingerprint shouldBe fingerprint
      JobLogEditorInput(key, JobLogContent("")).instanceUrl shouldBe ""
    }

    it("never show up in toString, name or tooltip") {
      val input = JobLogEditorInput(key, JobLogContent(""), url, fingerprint)
      listOf(input.toString(), input.name, input.toolTipText).forEach { shown ->
        shown shouldNotContain "secret-host"
        shown shouldNotContain fingerprint
      }
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

    it("reads the text of the current rendered result") {
      val content = JobLogContent("before")
      val input = JobLogEditorInput(key, content)
      content.rendered = RenderResult("rendered 新", StyleRunIndex.EMPTY, emptyList(), omitted = false)
      input.storage.contents.readBytes().toString(Charsets.UTF_8) shouldBe "rendered 新"
    }
  }
})
