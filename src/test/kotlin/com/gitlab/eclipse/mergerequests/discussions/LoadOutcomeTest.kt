package com.gitlab.eclipse.mergerequests.discussions

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class LoadOutcomeTest : DescribeSpec({
  describe("Failed.toString") {
    it("keeps the cause's message out and its type in") {
      "${LoadOutcome.Failed(java.io.IOException("https://gitlab.example.com/secret"))}" shouldBe
        "LoadOutcome.Failed(type=java.io.IOException)"
    }

    // A2(a): 例外型は「秘匿値の許された投影」。型を変えたら出力も変わる。
    // これが無いと toString を固定の定数にしても通る(設計 §22.1 の (v) 群)。
    it("reflects a different cause type") {
      "${LoadOutcome.Failed(IllegalStateException("https://gitlab.example.com/secret"))}" shouldBe
        "LoadOutcome.Failed(type=java.lang.IllegalStateException)"
    }
  }
})
