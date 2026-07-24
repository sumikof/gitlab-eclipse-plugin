package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.WebviewInfo
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun info(id: String, title: String = "T", uri: String? = "http://x") =
  WebviewInfo(id = id, title = title, uris = uri?.let { listOf(it) } ?: emptyList())

class ChatWebviewCatalogTest : DescribeSpec({

  describe("ChatWebviewCatalog.extract") {

    it("keeps only known chat ids in advertised order") {
      val out = ChatWebviewCatalog.extract(
        listOf(
          info("agentic-duo-chat", "Agentic", "u-a"),
          info("some-other"),
          info("duo-chat-v2", "Classic", "u-c"),
        ),
      )

      out.map { it.id } shouldBe listOf("agentic-duo-chat", "duo-chat-v2")
      out.first().uri shouldBe "u-a"
    }

    it("drops nulls, missing uri, unknown ids, and duplicates") {
      val out = ChatWebviewCatalog.extract(
        listOf(
          null,
          info("duo-chat-v2", "C", "u1"),
          info("duo-chat-v2", "C", "u2"),
          info("agentic-duo-chat", "A", null),
          info("unknown", "U", "u"),
        ),
      )

      out.map { it.id } shouldBe listOf("duo-chat-v2")
      out.first().uri shouldBe "u1"
    }

    it("returns empty for null or empty metadata") {
      ChatWebviewCatalog.extract(null) shouldBe emptyList()
      ChatWebviewCatalog.extract(emptyList()) shouldBe emptyList()
    }
  }
})
