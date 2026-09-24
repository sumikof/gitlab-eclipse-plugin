package com.gitlab.eclipse.knowledgegraph

import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.TreeMap

/**
 * Plan A20. The `getUrl` response arrives as whatever lsp4j's local receiver type says — `Object`,
 * i.e. a Gson `LinkedTreeMap` — never as a DTO, so the address is taken out of it by hand.
 */
class KnowledgeGraphUrlTest : DescribeSpec({

  describe("knowledgeGraphUrlOf") {
    it("Map の文字列 url を返す") {
      knowledgeGraphUrlOf(mapOf("url" to "http://localhost:1234")) shouldBe "http://localhost:1234"
    }

    it("Gson が Object として読んだ LinkedTreeMap からも url を返す") {
      val response = Gson().fromJson("{\"url\":\"http://localhost:1\"}", Any::class.java)

      knowledgeGraphUrlOf(response) shouldBe "http://localhost:1"
    }

    it("url が無い・文字列でない・空白のときは null") {
      knowledgeGraphUrlOf(null).shouldBeNull()
      knowledgeGraphUrlOf("str").shouldBeNull()
      knowledgeGraphUrlOf(mapOf("url" to 1)).shouldBeNull()
      knowledgeGraphUrlOf(mapOf("url" to "")).shouldBeNull()
      knowledgeGraphUrlOf(mapOf("url" to "  ")).shouldBeNull()
      knowledgeGraphUrlOf(mapOf("url" to null)).shouldBeNull()
      knowledgeGraphUrlOf(mapOf<String, Any>()).shouldBeNull()
      knowledgeGraphUrlOf(Gson().fromJson("{}", Any::class.java)).shouldBeNull()
    }

    it("DTO 風のオブジェクトやリストは Map ではないので null") {
      data class UrlHolder(val url: String)

      knowledgeGraphUrlOf(UrlHolder("http://localhost:1")).shouldBeNull()
      knowledgeGraphUrlOf(listOf("http://localhost:1")).shouldBeNull()
    }

    it("どんな入力でも ClassCastException を投げない") {
      // A TreeMap keyed by Int would throw from get("url"): the lookup must not compare keys.
      val intKeyed = TreeMap<Int, Any>().apply { put(1, "http://localhost:1") }

      knowledgeGraphUrlOf(intKeyed).shouldBeNull()
      knowledgeGraphUrlOf(mapOf(1 to "x", "url" to "http://localhost:2")) shouldBe "http://localhost:2"
    }
  }
})
