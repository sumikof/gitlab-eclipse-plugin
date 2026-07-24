package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.webview.ChatWebviewEntry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun entry(id: String) = ChatWebviewEntry(id, id, "u")

private fun availability(vararg pairs: Pair<String, Boolean>): Map<String, ChatAvailability> =
  pairs.associate { (id, enabled) -> id to ChatAvailability(id, enabled, null) }

private val classic = entry("duo-chat-v2")
private val agentic = entry("agentic-duo-chat")

class ChatSelectionResolverTest : DescribeSpec({

  describe("ChatSelectionResolver.resolve") {

    it("saved enabled wins") {
      ChatSelectionResolver.resolve(
        listOf(classic, agentic),
        availability("duo-chat-v2" to true, "agentic-duo-chat" to true),
        "agentic-duo-chat",
      ) shouldBe "agentic-duo-chat"
    }

    it("saved disabled falls back to enabled, classic preferred") {
      ChatSelectionResolver.resolve(
        listOf(classic, agentic),
        availability("duo-chat-v2" to true, "agentic-duo-chat" to false),
        "agentic-duo-chat",
      ) shouldBe "duo-chat-v2"
    }

    it("duo core selects agentic") {
      ChatSelectionResolver.resolve(
        listOf(classic, agentic),
        availability("duo-chat-v2" to false, "agentic-duo-chat" to true),
        null,
      ) shouldBe "agentic-duo-chat"
    }

    it("both enabled no saved prefers classic") {
      ChatSelectionResolver.resolve(
        listOf(classic, agentic),
        availability("duo-chat-v2" to true, "agentic-duo-chat" to true),
        null,
      ) shouldBe "duo-chat-v2"
    }

    it("all disabled returns first candidate") {
      ChatSelectionResolver.resolve(
        listOf(classic, agentic),
        availability("duo-chat-v2" to false, "agentic-duo-chat" to false),
        null,
      ) shouldBe "duo-chat-v2"
    }

    it("empty candidates returns null") {
      ChatSelectionResolver.resolve(emptyList(), emptyMap(), "duo-chat-v2") shouldBe null
    }
  }
})
