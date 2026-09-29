package com.gitlab.eclipse.views.inlinethread

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.swt.SWT

class InlineThreadRulesTest : DescribeSpec({
  describe("isSubmittable (moved from CommentInputDialog, #97)") {
    it("rejects blank bodies and accepts real content") {
      isSubmittable("") shouldBe false
      isSubmittable(" \n\t") shouldBe false
      isSubmittable(" ") shouldBe false
      isSubmittable(" hi ") shouldBe true
    }
  }

  describe("isSubmitChord") {
    it("accepts MOD1 + Enter") {
      isSubmitChord(SWT.MOD1, SWT.CR.code) shouldBe true
    }

    it("accepts MOD1 + keypad Enter") {
      isSubmitChord(SWT.MOD1, SWT.KEYPAD_CR) shouldBe true
    }

    it("accepts MOD1 + Enter with another modifier also held") {
      isSubmitChord(SWT.MOD1 or SWT.SHIFT, SWT.CR.code) shouldBe true
    }

    it("rejects a plain Enter (a newline in the input)") {
      isSubmitChord(0, SWT.CR.code) shouldBe false
      isSubmitChord(0, SWT.KEYPAD_CR) shouldBe false
    }

    it("rejects Shift + Enter without MOD1") {
      isSubmitChord(SWT.SHIFT, SWT.CR.code) shouldBe false
    }

    it("rejects MOD1 with another key") {
      isSubmitChord(SWT.MOD1, 'a'.code) shouldBe false
      isSubmitChord(SWT.MOD1, SWT.LF.code) shouldBe false
    }
  }

  describe("entryHeader") {
    it("is today's MR format when author and createdAt are both present") {
      entryHeader(InlineThreadEntry("alice", "2026-09-27 01:02", "body")) shouldBe "alice · 2026-09-27 01:02"
    }

    it("is the author alone when createdAt is empty") {
      entryHeader(InlineThreadEntry("You", "", "q")) shouldBe "You"
    }

    it("is absent when the author is empty") {
      entryHeader(InlineThreadEntry("", "", "New chat")).shouldBeNull()
      entryHeader(InlineThreadEntry("", "2026-09-27", "x")).shouldBeNull()
    }
  }

  describe("model defaults keep the MR popup as today (A18)") {
    it("an entry has no code blocks unless asked") {
      InlineThreadEntry("alice", "t", "b").codeBlocks shouldBe false
    }

    it("an item has no submit label override unless asked") {
      InlineThreadItem(
        threadId = "d1",
        title = "t",
        entries = emptyList(),
        resolved = null,
        moreEntriesOnServer = false,
        actions = setOf(InlineThreadAction.REPLY),
        inputPlaceholder = "Reply…",
      ).submitLabel.shouldBeNull()
    }
  }

  describe("submitLabelOf") {
    fun item(actions: Set<InlineThreadAction>, label: String? = null) = InlineThreadItem(
      threadId = "d1",
      title = "t",
      entries = emptyList(),
      resolved = null,
      moreEntriesOnServer = false,
      actions = actions,
      inputPlaceholder = "x",
      submitLabel = label,
    )

    it("is Comment for CREATE and Reply for REPLY by default") {
      submitLabelOf(item(setOf(InlineThreadAction.CREATE))) shouldBe "Comment"
      submitLabelOf(item(setOf(InlineThreadAction.REPLY, InlineThreadAction.RESOLVE))) shouldBe "Reply"
      submitLabelOf(item(setOf(InlineThreadAction.RESOLVE))).shouldBeNull()
    }

    it("uses the override when a text action exists") {
      submitLabelOf(item(setOf(InlineThreadAction.REPLY), "Send")) shouldBe "Send"
    }

    it("does not invent a submit button from the override alone") {
      submitLabelOf(item(setOf(InlineThreadAction.RESOLVE), "Send")).shouldBeNull()
    }
  }
})
