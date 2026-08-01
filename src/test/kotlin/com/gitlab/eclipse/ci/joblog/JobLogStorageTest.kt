package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class JobLogStorageTest : DescribeSpec({
  fun readAll(storage: JobLogStorage): String =
    storage.contents.readBytes().toString(Charsets.UTF_8)

  describe("getContents") {
    it("returns the current text as UTF-8 bytes") {
      val content = JobLogContent("hello log")
      val storage = JobLogStorage(content, "job-1.log")
      readAll(storage) shouldBe "hello log"
    }

    it("reflects a later content.text update on a fresh read (no constructor snapshot)") {
      val content = JobLogContent("old")
      val storage = JobLogStorage(content, "job-1.log")
      readAll(storage) shouldBe "old"
      content.text = "新しい"
      readAll(storage) shouldBe "新しい"
    }

    it("round-trips non-ASCII Japanese text through UTF-8") {
      val content = JobLogContent("ジョブログ: 成功\n終了コード 0")
      val storage = JobLogStorage(content, "job-2.log")
      readAll(storage) shouldBe "ジョブログ: 成功\n終了コード 0"
    }
  }

  describe("storage contract") {
    val storage = JobLogStorage(JobLogContent(""), "job-42.log")

    it("pins charset to UTF-8") {
      storage.charset shouldBe "UTF-8"
    }

    it("is read-only") {
      storage.isReadOnly shouldBe true
    }

    it("has no full path") {
      storage.fullPath shouldBe null
    }

    it("returns the passed name") {
      storage.name shouldBe "job-42.log"
    }
  }
})
