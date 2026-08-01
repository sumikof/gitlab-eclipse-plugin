package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain

class JobLogKeyTest : DescribeSpec({
  val url = "https://gitlab.example.com"
  val fingerprint = "abcdef0123456789"

  describe("JobLogKey.of") {
    it("is stable: same inputs produce equal keys") {
      val a = JobLogKey.of(url, fingerprint, 1L, 2L)
      val b = JobLogKey.of(url, fingerprint, 1L, 2L)
      a shouldBe b
      a.hashCode() shouldBe b.hashCode()
    }

    it("normalizes the instance url: trailing slash does not change connHash") {
      val withSlash = JobLogKey.of("https://x/", fingerprint, 1L, 2L)
      val withoutSlash = JobLogKey.of("https://x", fingerprint, 1L, 2L)
      withSlash.connHash shouldBe withoutSlash.connHash
      withSlash shouldBe withoutSlash
    }

    it("different instanceUrl produces a different connHash") {
      val a = JobLogKey.of("https://gitlab.example.com", fingerprint, 1L, 2L)
      val b = JobLogKey.of("https://other.example.org", fingerprint, 1L, 2L)
      a.connHash shouldNotBe b.connHash
    }

    it("different authFingerprint on the same url produces a different connHash") {
      val a = JobLogKey.of(url, "fingerprint-one", 1L, 2L)
      val b = JobLogKey.of(url, "fingerprint-two", 1L, 2L)
      a.connHash shouldNotBe b.connHash
    }

    it("same connection but different jobId produces a different key") {
      val a = JobLogKey.of(url, fingerprint, 1L, 2L)
      val b = JobLogKey.of(url, fingerprint, 1L, 3L)
      a shouldNotBe b
      a.connHash shouldBe b.connHash
    }

    it("same connection but different projectId produces a different key") {
      val a = JobLogKey.of(url, fingerprint, 1L, 2L)
      val b = JobLogKey.of(url, fingerprint, 9L, 2L)
      a shouldNotBe b
    }

    it("connHash is 16 lowercase-hex chars and non-reversible") {
      val key = JobLogKey.of(url, fingerprint, 1L, 2L)
      key.connHash shouldMatch Regex("[0-9a-f]{16}")
      key.connHash shouldNotContain "example.com"
      key.connHash shouldNotContain fingerprint
    }
  }
})
