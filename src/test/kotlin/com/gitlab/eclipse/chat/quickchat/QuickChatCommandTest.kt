package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class QuickChatCommandTest : DescribeSpec({

  describe("QuickChatCommand.classify") {

    it("classifies /clear") {
      QuickChatCommand.classify("/clear") shouldBe QuickChatCommand.Clear
    }

    it("classifies /reset") {
      QuickChatCommand.classify("/reset") shouldBe QuickChatCommand.Reset
    }

    it("classifies with surrounding whitespace and mixed case (R5: trim().lowercase())") {
      QuickChatCommand.classify("  /CLEAR  ") shouldBe QuickChatCommand.Clear
      QuickChatCommand.classify("  /ReSeT  ") shouldBe QuickChatCommand.Reset
    }

    it("classifies anything else as a Question carrying the raw, untrimmed text") {
      QuickChatCommand.classify("  What does this do?  ") shouldBe
        QuickChatCommand.Question("  What does this do?  ")
    }

    it("does not classify a command embedded in a longer question") {
      QuickChatCommand.classify("/clear the cache please") shouldBe
        QuickChatCommand.Question("/clear the cache please")
    }
  }

  describe("QuickChatCommand.Clear/Reset literal (M2 question sent to the server, design §9.4)") {
    it("Clear.literal is /clear") {
      QuickChatCommand.Clear.literal shouldBe "/clear"
    }

    it("Reset.literal is /reset") {
      QuickChatCommand.Reset.literal shouldBe "/reset"
    }
  }
})
