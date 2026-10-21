package com.gitlab.eclipse.lsp

import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class FeatureStateChangeContextTest : DescribeSpec({
  describe("FeatureStateChangeCheck") {
    it("deserialises the context object") {
      val json = """{"checkId":"x","engaged":true,"context":{"baseUrl":"https://a","version":"1.2"}}"""
      val check = Gson().fromJson(json, FeatureStateChangeCheck::class.java)
      check.context!!.get("baseUrl").asString shouldBe "https://a"
      check.context!!.get("version").asString shouldBe "1.2"
    }
    it("defaults context to null") {
      Gson().fromJson("""{"checkId":"x","engaged":true}""", FeatureStateChangeCheck::class.java).context shouldBe null
    }
  }
})
