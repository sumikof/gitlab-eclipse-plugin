package com.gitlab.eclipse.security.details

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/**
 * 設計 §10。スナップショットは不変で、呼び出し側のリストを後から書き換えても変わらない。
 * `toString` は所見本文と fingerprint を出さない(A7 / §15)。
 */
class FileVulnerabilitiesTest : DescribeSpec({

  describe("不変性") {
    it("呼び出し側のリストを後から変更しても影響を受けない") {
      val source = mutableListOf<Any?>("a")
      val snapshot = FileVulnerabilities(source, 1L, 2L, "fp")
      source.add("b")
      snapshot.findings shouldContainExactly listOf("a")
    }

    it("保持したリストは書き換えられない") {
      val snapshot = FileVulnerabilities(listOf("a"), 1L, 2L, "fp")
      shouldThrow<UnsupportedOperationException> {
        @Suppress("UNCHECKED_CAST")
        (snapshot.findings as MutableList<Any?>).add("b")
      }
    }
  }

  describe("値としての等価") {
    it("4 成分が等しければ等しい") {
      FileVulnerabilities(listOf("a"), 1L, 2L, "fp") shouldBe FileVulnerabilities(mutableListOf("a"), 1L, 2L, "fp")
      FileVulnerabilities(listOf("a"), 1L, 2L, "fp").hashCode() shouldBe
        FileVulnerabilities(listOf("a"), 1L, 2L, "fp").hashCode()
    }

    it("どの成分が違っても等しくない") {
      val base = FileVulnerabilities(listOf("a"), 1L, 2L, "fp")
      base shouldNotBe FileVulnerabilities(listOf("b"), 1L, 2L, "fp")
      base shouldNotBe FileVulnerabilities(listOf("a"), null, 2L, "fp")
      base shouldNotBe FileVulnerabilities(listOf("a"), 1L, 3L, "fp")
      base shouldNotBe FileVulnerabilities(listOf("a"), 1L, 2L, "other")
    }
  }

  describe("toString(A7)") {
    it("所見本文も fingerprint も出さず、件数と世代だけを出す") {
      val text = FileVulnerabilities(listOf(mapOf("description" to "secret-body")), 1L, 7L, "deadbeef").toString()
      text shouldNotContain "secret-body"
      text shouldNotContain "deadbeef"
      text shouldBe "FileVulnerabilities(findings=1, epoch=7)"
    }
  }
})
