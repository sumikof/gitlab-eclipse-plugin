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

  describe("★ 入れ子の JSON 値も深くコピーして凍結する(PR #89 Codex P1)") {
    // 応答の所見は Gson の可変 LinkedTreeMap / ArrayList。外側のリストだけをコピーすると、入れ子は応答・store・
    // read の返り値のあいだで共有され、intake の錠も RecordPermit も通らずに保持中の所見を書き換えられる。
    fun nestedSource(): MutableList<Any?> = mutableListOf(
      mutableMapOf<String, Any?>(
        "name" to "a",
        "location" to mutableMapOf<String, Any?>("start_line" to 3),
        "identifiers" to mutableListOf<Any?>(mutableMapOf<String, Any?>("value" to "CWE-79")),
      )
    )

    it("呼び出し側が入れ子の Map / List を後から変更しても影響を受けない") {
      val source = nestedSource()
      val snapshot = FileVulnerabilities(source, 1L, 2L, "fp")
      val finding = source[0] as MutableMap<String, Any?>
      finding["name"] = "tampered"
      (finding["location"] as MutableMap<String, Any?>)["start_line"] = 99
      val identifiers = finding["identifiers"] as MutableList<Any?>
      (identifiers[0] as MutableMap<String, Any?>)["value"] = "CWE-0"
      identifiers.add("extra")

      snapshot.findings shouldBe listOf(
        mapOf(
          "name" to "a",
          "location" to mapOf("start_line" to 3),
          "identifiers" to listOf(mapOf("value" to "CWE-79")),
        )
      )
    }

    it("保持した入れ子の Map はどの深さでも書き換えられない") {
      val snapshot = FileVulnerabilities(nestedSource(), 1L, 2L, "fp")
      val finding = snapshot.findings[0] as MutableMap<String, Any?>
      shouldThrow<UnsupportedOperationException> { finding["name"] = "x" }
      shouldThrow<UnsupportedOperationException> { (finding["location"] as MutableMap<String, Any?>)["start_line"] = 1 }
      val identifier = (finding["identifiers"] as List<*>)[0] as MutableMap<String, Any?>
      shouldThrow<UnsupportedOperationException> { identifier["value"] = "x" }
    }

    it("保持した入れ子の List は書き換えられない") {
      val snapshot = FileVulnerabilities(nestedSource(), 1L, 2L, "fp")
      val identifiers = (snapshot.findings[0] as Map<*, *>)["identifiers"] as MutableList<Any?>
      shouldThrow<UnsupportedOperationException> { identifiers.add("x") }
    }

    it("キーの順序と JSON のスカラー値(文字列・数値・真偽・null)はそのまま保つ") {
      val source = listOf(linkedMapOf<String, Any?>("z" to "s", "a" to 1.5, "m" to true, "n" to null))
      val finding = FileVulnerabilities(source, 1L, 2L, "fp").findings[0] as Map<*, *>
      finding.keys.toList() shouldContainExactly listOf("z", "a", "m", "n")
      finding shouldBe mapOf("z" to "s", "a" to 1.5, "m" to true, "n" to null)
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
