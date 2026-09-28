package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class GitLabVersionTest : DescribeSpec({

  describe("GitLabVersion.parse") {
    it("parses a plain major.minor.patch version") {
      GitLabVersion.parse("17.10.0") shouldBe GitLabVersion(17, 10)
    }

    it("parses a pre-release suffix") {
      GitLabVersion.parse("17.10.0-pre") shouldBe GitLabVersion(17, 10)
    }

    it("parses an -ee suffix") {
      GitLabVersion.parse("18.2.1-ee") shouldBe GitLabVersion(18, 2)
    }

    it("parses with surrounding whitespace and no patch segment") {
      GitLabVersion.parse(" 17.10 ") shouldBe GitLabVersion(17, 10)
    }

    it("returns null for null input") {
      GitLabVersion.parse(null) shouldBe null
    }

    it("returns null for garbage input") {
      GitLabVersion.parse("not-a-version") shouldBe null
    }

    it("returns null for empty input") {
      GitLabVersion.parse("") shouldBe null
    }

    it("returns null when only a major segment is present") {
      GitLabVersion.parse("17") shouldBe null
    }
  }

  describe("GitLabVersion.supportsQuickChat (design §6.4 confirmed fact 1: 17.10+)") {
    it("17.10 supports it") {
      GitLabVersion(17, 10).supportsQuickChat() shouldBe true
    }

    it("17.11 supports it") {
      GitLabVersion(17, 11).supportsQuickChat() shouldBe true
    }

    it("18.0 supports it") {
      GitLabVersion(18, 0).supportsQuickChat() shouldBe true
    }

    it("17.9 does not support it") {
      GitLabVersion(17, 9).supportsQuickChat() shouldBe false
    }

    it("16.11 does not support it") {
      GitLabVersion(16, 11).supportsQuickChat() shouldBe false
    }
  }
})
