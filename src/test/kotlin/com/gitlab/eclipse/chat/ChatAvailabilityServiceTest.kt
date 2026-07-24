package com.gitlab.eclipse.chat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ChatAvailabilityServiceTest : DescribeSpec({

  describe("ChatAvailabilityService pure aggregation") {

    it("no state received yet -> everything disabled") {
      val service = ChatAvailabilityService()

      service.anyChatEnabled shouldBe false
      service.availabilityFor("duo-chat-v2").enabled shouldBe false
      service.availabilityFor("agentic-duo-chat").enabled shouldBe false
      service.availabilityFor("duo-chat-v2").disabledReason shouldBe null
    }

    it("classic only enabled -> any true") {
      val service = ChatAvailabilityService()
      service.applyForTest("chat", enabled = true, reason = null)

      service.anyChatEnabled shouldBe true
      service.availabilityFor("duo-chat-v2").enabled shouldBe true
      service.availabilityFor("agentic-duo-chat").enabled shouldBe false
    }

    it("agentic only enabled -> any true") {
      val service = ChatAvailabilityService()
      service.applyForTest("agentic_chat", enabled = true, reason = null)

      service.anyChatEnabled shouldBe true
      service.availabilityFor("agentic-duo-chat").enabled shouldBe true
      service.availabilityFor("duo-chat-v2").enabled shouldBe false
    }

    it("both disabled -> any false with reason") {
      val service = ChatAvailabilityService()
      service.applyForTest("chat", enabled = false, reason = "no license")
      service.applyForTest("agentic_chat", enabled = false, reason = "flag off")

      service.anyChatEnabled shouldBe false
      service.availabilityFor("duo-chat-v2").disabledReason shouldBe "no license"
      service.availabilityFor("agentic-duo-chat").disabledReason shouldBe "flag off"
    }

    it("enabled webview has null disabledReason") {
      val service = ChatAvailabilityService()
      service.applyForTest("chat", enabled = true, reason = null)

      service.availabilityFor("duo-chat-v2").disabledReason shouldBe null
    }

    it("unknown webview id -> disabled with null reason") {
      val service = ChatAvailabilityService()
      service.applyForTest("chat", enabled = true, reason = null)

      val unknown = service.availabilityFor("something-else")
      unknown.enabled shouldBe false
      unknown.disabledReason shouldBe null
    }

    it("getCurrentState exposes duo_chat_available") {
      val service = ChatAvailabilityService()
      service.applyForTest("agentic_chat", enabled = true, reason = null)

      service.currentState[ChatAvailabilityService.DUO_CHAT_AVAILABLE_KEY] shouldBe true
      service.providedSourceNames shouldBe arrayOf(ChatAvailabilityService.DUO_CHAT_AVAILABLE_KEY)
    }
  }
})
