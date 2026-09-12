package com.gitlab.eclipse.lsp.messages

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LspClientMessageParamsTest : DescribeSpec({
  describe("OpenFileParams") {
    it("carries a null filePath") {
      OpenFileParams(null).filePath.shouldBeNull()
    }
    it("round-trips its value") {
      OpenFileParams("a/b.kt").filePath shouldBe "a/b.kt"
    }
  }

  describe("CopyTextParams") {
    it("carries a null text") {
      CopyTextParams(null).text.shouldBeNull()
    }
    it("round-trips its value") {
      CopyTextParams("copied text").text shouldBe "copied text"
    }
  }

  describe("usablePayload") {
    it("returns null for a null value") {
      usablePayload(null).shouldBeNull()
    }
    it("returns null for an empty value") {
      usablePayload("").shouldBeNull()
    }
    it("returns null for a value of only spaces") {
      usablePayload("   ").shouldBeNull()
    }
    it("returns null for a value of only tab and newline") {
      usablePayload("\t\n").shouldBeNull()
    }
    it("returns the value unchanged, without trimming, when it has surrounding whitespace") {
      usablePayload("  x  ") shouldBe "  x  "
    }
    it("returns the value unchanged for a plain non-blank value") {
      usablePayload("x") shouldBe "x"
    }
  }
})
