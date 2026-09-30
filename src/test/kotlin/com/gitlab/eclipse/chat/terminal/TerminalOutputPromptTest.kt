package com.gitlab.eclipse.chat.terminal

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class TerminalOutputPromptTest : DescribeSpec({

  describe("build") {
    it("sends the text as the selected text of a fixed, file-less context") {
      val built = TerminalOutputPrompt.build("npm ERR! code ENOENT")

      built.request.prompt shouldBe "explainTerminalOutput"
      val context = built.request.fileContext!!
      context.fileName shouldBe TerminalOutputPrompt.CONTEXT_NAME
      context.selectedText shouldBe "npm ERR! code ENOENT"
      context.contentAboveCursor shouldBe ""
      context.contentBelowCursor shouldBe ""
      built.truncated shouldBe false
    }

    it("keeps text of exactly the limit whole") {
      val text = "a".repeat(TerminalOutputPrompt.MAX_LENGTH)

      val built = TerminalOutputPrompt.build(text)

      built.request.fileContext!!.selectedText shouldBe text
      built.truncated shouldBe false
    }

    it("keeps the tail of longer text, where the latest output is") {
      val text = "HEAD" + "x".repeat(TerminalOutputPrompt.MAX_LENGTH - 4) + "TAIL"

      val built = TerminalOutputPrompt.build(text)

      val sent = built.request.fileContext!!.selectedText
      sent.length shouldBe TerminalOutputPrompt.MAX_LENGTH
      sent shouldEndWith "TAIL"
      built.truncated shouldBe true
    }

    it("never starts the kept tail with half of a surrogate pair") {
      val emoji = "😀"
      // One character too long, so a plain cut would keep only the low surrogate of the emoji.
      val text = emoji + "b".repeat(TerminalOutputPrompt.MAX_LENGTH - 1)

      val sent = TerminalOutputPrompt.build(text).request.fileContext!!.selectedText

      Character.isLowSurrogate(sent.first()) shouldBe false
      sent shouldBe "b".repeat(TerminalOutputPrompt.MAX_LENGTH - 1)
    }
  }

  describe("independence (no shared state between prompts)") {
    it("builds independent requests for successive selections") {
      val first = TerminalOutputPrompt.build("first")
      val second = TerminalOutputPrompt.build("second")

      first.request.fileContext!!.selectedText shouldBe "first"
      second.request.fileContext!!.selectedText shouldBe "second"
    }
  }
})
