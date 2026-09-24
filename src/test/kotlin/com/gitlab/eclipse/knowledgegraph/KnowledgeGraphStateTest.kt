package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.webview.DirectWebview
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val URL_A = "http://localhost:1111"
private const val URL_B = "http://localhost:2222"

/**
 * Plan §8.1 / §11 / A14 / A25. The state is a global object, so every case uses fresh
 * [LanguageServerSession] instances: a value left behind by another case is bound to a session
 * this case never passes, and so can never answer here.
 */
class KnowledgeGraphStateTest : DescribeSpec({

  describe("record と読み出し") {
    it("同じ session には記録した URL を返す") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)

      KnowledgeGraphState.urlFor(session) shouldBe URL_A
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID, session) shouldBe
        DirectWebview("Knowledge Graph", URL_A)
    }

    it("別の session には null を返す") {
      val session = LanguageServerSession()
      val other = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)

      KnowledgeGraphState.urlFor(other).shouldBeNull()
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID, other).shouldBeNull()
    }

    it("後勝ちで上書きする(ready と getUrl が同じ接続から届く)") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)
      KnowledgeGraphState.record(URL_B, session, session)

      KnowledgeGraphState.urlFor(session) shouldBe URL_B
    }
  }

  describe("A25(i) / A14: 旧接続の遅延報告") {
    it("新接続の値を記録した後に旧接続の遅延 record が来ても新しい値が残る") {
      val old = LanguageServerSession()
      val current = LanguageServerSession()
      KnowledgeGraphState.record(URL_B, current, current)

      KnowledgeGraphState.record(URL_A, old, current)

      KnowledgeGraphState.urlFor(current) shouldBe URL_B
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID, current)?.uri shouldBe URL_B
    }

    it("旧接続の URL は現行 session に対して決して返らない") {
      val old = LanguageServerSession()
      val current = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, old, old)

      KnowledgeGraphState.urlFor(current).shouldBeNull()
      KnowledgeGraphState.directWebviewFor(KnowledgeGraphState.WEBVIEW_ID, current).shouldBeNull()
    }

    it("sender と current が異なれば捨て、保持中の値に触れない") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)

      KnowledgeGraphState.record(URL_B, LanguageServerSession(), LanguageServerSession())

      KnowledgeGraphState.urlFor(session) shouldBe URL_A
    }
  }

  describe("A25(ii): 現行 session が無い") {
    it("currentSession が null なら記録せず、保持中の値も残る") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)

      KnowledgeGraphState.record(URL_B, session, null)

      KnowledgeGraphState.urlFor(session) shouldBe URL_A
    }

    it("何も保持していない session には null のまま") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, null)

      KnowledgeGraphState.urlFor(session).shouldBeNull()
    }
  }

  describe("null / ブランクの URL") {
    it("既存の値を壊さない") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)

      KnowledgeGraphState.record(null, session, session)
      KnowledgeGraphState.record("", session, session)
      KnowledgeGraphState.record("   ", session, session)

      KnowledgeGraphState.urlFor(session) shouldBe URL_A
    }
  }

  describe("他の webview には答えない") {
    it("別 id には URL を保持していても null を返す") {
      val session = LanguageServerSession()
      KnowledgeGraphState.record(URL_A, session, session)

      KnowledgeGraphState.directWebviewFor("duo-chat-v2", session).shouldBeNull()
      KnowledgeGraphState.directWebviewFor("agentic-tabs", session).shouldBeNull()
      KnowledgeGraphState.directWebviewFor("root/mcp", session).shouldBeNull()
      KnowledgeGraphState.directWebviewFor("", session).shouldBeNull()
    }
  }

  describe("定数は同梱 LS の実値") {
    it("KNOWLEDGE_GRAPH_WEBVIEW_ID / KNOWLEDGE_GRAPH_WEBVIEW_TITLE") {
      KnowledgeGraphState.WEBVIEW_ID shouldBe "knowledge-graph"
      KnowledgeGraphState.TITLE shouldBe "Knowledge Graph"
    }

    it("案内文は <p> へそのまま入るので HTML の特殊文字を含まない") {
      listOf("<", ">", "&", "`").forEach { KnowledgeGraphState.NOT_RUNNING_MESSAGE shouldNotContain it }
    }
  }

  describe("A25(iii): wrapper へ依存しない") {
    it("宣言フィールドにもメソッド引数・戻り値にも GitLabLanguageServerWrapper が現れない") {
      val type = KnowledgeGraphState::class.java
      val referenced =
        type.declaredFields.map { it.type } +
          type.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType } +
          type.declaredConstructors.flatMap { it.parameterTypes.toList() }

      referenced shouldNotContain GitLabLanguageServerWrapper::class.java
    }
  }

  describe("DirectWebview.toString") {
    it("uri を出さない") {
      DirectWebview("Knowledge Graph", URL_A).toString() shouldNotContain "localhost"
    }
  }
})
