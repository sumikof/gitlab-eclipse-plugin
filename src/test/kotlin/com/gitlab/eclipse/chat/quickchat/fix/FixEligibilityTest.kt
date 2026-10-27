package com.gitlab.eclipse.chat.quickchat.fix

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class FixEligibilityTest : DescribeSpec({

  data class Case(val name: String, val facts: MarkerFacts, val expected: Boolean)

  val ok = MarkerFacts(exists = true, isProblem = true, severity = 2, onFile = true)

  describe("isFixEligible") {
    withData(
      nameFn = { it.name },
      Case("error", ok, true),
      Case("warning", ok.copy(severity = 1), true),
      Case("info", ok.copy(severity = 0), false),
      Case("unknown severity -1", ok.copy(severity = -1), false),
      Case("unknown severity 3", ok.copy(severity = 3), false),
      Case("marker gone", ok.copy(exists = false), false),
      Case("not a problem marker", ok.copy(isProblem = false), false),
      Case("not on a file", ok.copy(onFile = false), false),
    ) { c ->
      isFixEligible({ true }, { c.facts }) shouldBe c.expected
    }

    it("does not read the marker while Duo Chat is disabled") {
      isFixEligible({ false }, { error("must not be called") }) shouldBe false
    }

    it("is false when reading the marker throws") {
      isFixEligible({ true }, { throw IllegalStateException("boom") }) shouldBe false
    }

    it("is false when the chat check throws") {
      isFixEligible({ throw IllegalStateException("boom") }, { ok }) shouldBe false
    }
  }
})
