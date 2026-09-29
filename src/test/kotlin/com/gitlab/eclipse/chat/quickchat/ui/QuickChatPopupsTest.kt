package com.gitlab.eclipse.chat.quickchat.ui

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class QuickChatPopupsTest : DescribeSpec({
  val editor = Any()
  val otherEditor = Any()

  describe("decidePlacement") {
    it("opens a new popup when the window has none") {
      decidePlacement(existing = null, editor = editor, oneBasedLine = 3) shouldBe PopupPlacement.NEW
    }

    it("re-activates the popup of the same editor and line") {
      decidePlacement(OpenPlacement(editor, 3), editor, 3) shouldBe PopupPlacement.ACTIVATE
    }

    it("replaces the popup of another line of the same editor") {
      decidePlacement(OpenPlacement(editor, 3), editor, 4) shouldBe PopupPlacement.REPLACE
    }

    it("replaces the popup of another editor, compared by identity") {
      decidePlacement(OpenPlacement(otherEditor, 3), editor, 3) shouldBe PopupPlacement.REPLACE
      val equalButDistinct1 = "same".toCharArray().concatToString()
      val equalButDistinct2 = "same".toCharArray().concatToString()
      decidePlacement(OpenPlacement(equalButDistinct1, 3), equalButDistinct2, 3) shouldBe PopupPlacement.REPLACE
    }
  }

  describe("QuickChatUiTexts") {
    it("titles the popup with its line") {
      QuickChatUiTexts.popupTitle(12) shouldBe "GitLab Duo Quick Chat — line 12"
    }
  }
})
