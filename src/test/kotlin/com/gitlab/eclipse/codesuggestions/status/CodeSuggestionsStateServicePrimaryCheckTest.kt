package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private fun change(vararg checks: Pair<String, Boolean>) =
  FeatureStateChange("code_suggestions", checks.map { (id, engaged) -> FeatureStateChangeCheck(id, engaged) })

/**
 * Primary-check status of [CodeSuggestionsStateService] (design §8.3): headless, the refresh is the
 * injected `onStatusChanged` seam, never the display.
 */
class CodeSuggestionsStateServicePrimaryCheckTest : DescribeSpec({
  class Fixture {
    var current: LanguageServerSession? = LanguageServerSession()
    var statusChanges = 0
    val forwarded = mutableListOf<Pair<LanguageServerSession, List<FeatureStateChangeCheck>?>>()
    val service = CodeSuggestionsStateService(
      currentSession = { current },
      onStatusChanged = { statusChanges++ },
      onFeatureState = { session, checks -> forwarded += session to checks },
    )
  }

  describe("before any feature state") {
    it("is unknown, disabled and has no primary check") {
      val f = Fixture()
      f.service.featureStateKnown shouldBe false
      f.service.primaryCheckId shouldBe null
      f.service.isEnabled shouldBe false
    }
  }

  describe("update(change, session)") {
    it("records the primary check and signals the first known state") {
      val f = Fixture()

      f.service.update(change("code-suggestions-api-error" to true), f.current!!)

      f.service.featureStateKnown shouldBe true
      f.service.primaryCheckId shouldBe "code-suggestions-api-error"
      f.statusChanges shouldBe 1
    }

    it("signals a primary change that does not flip isEnabled (api-error -> no-license)") {
      val f = Fixture()
      f.service.update(change("code-suggestions-api-error" to true), f.current!!)

      f.service.update(
        change("code-suggestions-api-error" to false, "code-suggestions-no-license" to true),
        f.current!!,
      )

      f.service.isEnabled shouldBe false
      f.service.primaryCheckId shouldBe "code-suggestions-no-license"
      f.statusChanges shouldBe 2
    }

    it("does not signal when the primary check stays the same") {
      val f = Fixture()
      f.service.update(change("code-suggestions-no-license" to true), f.current!!)

      f.service.update(
        change("code-suggestions-no-license" to true, "code-suggestions-no-credits" to true),
        f.current!!,
      )

      f.statusChanges shouldBe 1
      f.service.engagedCheckIds() shouldContainExactly
        listOf("code-suggestions-no-license", "code-suggestions-no-credits")
    }

    it("signals when every check disengages, and becomes enabled") {
      val f = Fixture()
      f.service.update(change("code-suggestions-no-license" to true), f.current!!)

      f.service.update(change("code-suggestions-no-license" to false), f.current!!)

      f.service.isEnabled shouldBe true
      f.service.primaryCheckId shouldBe null
      f.service.getFirstEngagedCheck() shouldBe null
      f.statusChanges shouldBe 2
    }

    it("forwards the current session's checks to the warning hook") {
      val f = Fixture()
      val update = change("code-suggestions-no-credits" to true)

      f.service.update(update, f.current!!)

      f.forwarded shouldContainExactly listOf(f.current!! to update.allChecks)
    }

    it("drops an update of a stale session entirely") {
      val f = Fixture()

      f.service.update(change("code-suggestions-no-credits" to true), LanguageServerSession())

      f.service.featureStateKnown shouldBe false
      f.statusChanges shouldBe 0
      f.forwarded.shouldBeEmpty()
    }
  }

  describe("reset()") {
    it("returns to unknown and disabled, and signals once") {
      val f = Fixture()
      f.service.update(change("code-suggestions-no-license" to false), f.current!!)
      f.service.isEnabled shouldBe true

      f.service.reset()

      f.service.isEnabled shouldBe false
      f.service.featureStateKnown shouldBe false
      f.service.primaryCheckId shouldBe null
      f.service.engagedCheckIds().shouldBeEmpty()
      f.statusChanges shouldBe 2

      f.service.reset()
      f.statusChanges shouldBe 2
    }
  }
})
