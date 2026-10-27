package com.gitlab.eclipse.chat.quickchat.fix

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class FixMessageTest : DescribeSpec({

  describe("FixMessage.of (VS Code #getFixMessage)") {
    it("is the bare command without diagnostics") {
      FixMessage.of(emptyList()) shouldBe "/fix"
    }

    it("quotes a single diagnostic with its line") {
      FixMessage.of(listOf(FixDiagnostic("Missing semicolon", 3))) shouldBe
        "/fix `Missing semicolon` on line 3"
    }

    it("lists multiple diagnostics and does not escape backticks") {
      FixMessage.of(listOf(FixDiagnostic("a", 1), FixDiagnostic("b `x`", 10))) shouldBe
        "/fix multiple issues: \n\n`a` on line 1\n`b `x`` on line 10"
    }
  }

  describe("FixMessage.LABEL") {
    it("is the VS Code action title") {
      FixMessage.LABEL shouldBe "Fix with GitLab Duo"
    }
  }
})
