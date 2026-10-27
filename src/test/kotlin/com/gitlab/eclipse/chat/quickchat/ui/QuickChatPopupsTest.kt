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

  describe("sendIfOpened") {
    class FakePopup(val isOpen: Boolean, val accepts: Boolean = true) {
      val sent = mutableListOf<String>()
    }

    fun send(popup: FakePopup?) = sendIfOpened(
      popup,
      isOpen = { it.isOpen },
      submit = { p, message -> p.sent += message; p.accepts },
      message = "/fix",
    )

    it("sends nothing when the popup failed to open") {
      send(null) shouldBe false
    }

    it("sends nothing to a popup that is already closed") {
      val popup = FakePopup(isOpen = false)
      send(popup) shouldBe false
      popup.sent shouldBe emptyList()
    }

    it("sends the message once to an open popup") {
      val popup = FakePopup(isOpen = true)
      send(popup) shouldBe true
      popup.sent shouldBe listOf("/fix")
    }

    it("reports a submit the popup refused") {
      val popup = FakePopup(isOpen = true, accepts = false)
      send(popup) shouldBe false
      popup.sent shouldBe listOf("/fix")
    }
  }

  describe("QuickChatUiTexts") {
    it("titles the popup with its line") {
      QuickChatUiTexts.popupTitle(12) shouldBe "GitLab Duo Quick Chat — line 12"
    }
  }
})
