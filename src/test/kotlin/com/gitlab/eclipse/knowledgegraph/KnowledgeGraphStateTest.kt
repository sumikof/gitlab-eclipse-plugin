package com.gitlab.eclipse.knowledgegraph

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** 設計 §8.1 / §11 / A8。 */
class KnowledgeGraphStateTest : DescribeSpec({

  beforeEach { KnowledgeGraphState.clear() }
  afterEach { KnowledgeGraphState.clear() }

  describe("記録") {
    it("報告された URL を保持する") {
      KnowledgeGraphState.record("http://localhost:1234")
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID)?.uri shouldBe
        "http://localhost:1234"
    }

    it("タイトルはサーバの語彙を使う") {
      KnowledgeGraphState.record("http://localhost:1234")
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID)?.title shouldBe
        "Knowledge Graph"
    }

    it("null やブランクは既存の値を壊さない") {
      KnowledgeGraphState.record("http://localhost:1234")
      KnowledgeGraphState.record(null)
      KnowledgeGraphState.record("   ")
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID)?.uri shouldBe
        "http://localhost:1234"
    }

    it("後勝ちで上書きする(ready と getUrl の競合)") {
      KnowledgeGraphState.record("http://localhost:1")
      KnowledgeGraphState.record("http://localhost:2")
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID)?.uri shouldBe
        "http://localhost:2"
    }
  }

  describe("未記録(A8)") {
    it("URL が無ければ null を返し、例外を投げない") {
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID).shouldBeNull()
    }

    it("clear で忘れる") {
      KnowledgeGraphState.record("http://localhost:1234")
      KnowledgeGraphState.clear()
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID).shouldBeNull()
    }
  }

  describe("他の webview には答えない") {
    it("別 id には null を返す(既存の解決経路を乱さない)") {
      KnowledgeGraphState.record("http://localhost:1234")
      KnowledgeGraphState.directWebviewFor("duo-chat-v2").shouldBeNull()
      KnowledgeGraphState.directWebviewFor("agentic-tabs").shouldBeNull()
      KnowledgeGraphState.directWebviewFor("root/mcp").shouldBeNull()
    }
  }

  describe("id は同梱 LS の実値") {
    it("knowledge-graph") {
      KnowledgeGraphState.WEBVIEW_ID shouldBe "knowledge-graph"
    }
  }
})
