package com.gitlab.eclipse.mergerequests.discussions

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * Covers only the pure [isSubmittable] rule. The dialog itself is SWT-bound and cannot be
 * constructed headless (no display in this environment); its widget behaviour is verified
 * manually on a real Eclipse per the PR's manual checklist (design §18.2).
 */
class CommentInputDialogTest : DescribeSpec({
  describe("isSubmittable") {
    it("rejects the empty string") {
      isSubmittable("") shouldBe false
    }

    it("rejects a spaces-only body") {
      isSubmittable("   ") shouldBe false
    }

    it("rejects a body of mixed whitespace kinds (newline, tab, space)") {
      isSubmittable("\n\t ") shouldBe false
    }

    it("accepts a plain word") {
      isSubmittable("hi") shouldBe true
    }

    it("accepts real content despite leading and trailing whitespace") {
      isSubmittable(" hi ") shouldBe true
    }

    it("rejects a lone non-breaking space, because Kotlin's Char.isWhitespace() is true for U+00A0") {
      // Kotlin's String.trim() trims by Char.isWhitespace(), which is
      // Character.isWhitespace(c) || Character.isSpaceChar(c). Java's isWhitespace() alone is
      // false for NO-BREAK SPACE, but isSpaceChar() is true (Unicode category SPACE_SEPARATOR),
      // so Kotlin DOES trim U+00A0 and a body of only NBSPs is not submittable. This asserts
      // the behaviour observed by running this test, not an assumed one.
      isSubmittable("\u00A0") shouldBe false
    }
  }
})
