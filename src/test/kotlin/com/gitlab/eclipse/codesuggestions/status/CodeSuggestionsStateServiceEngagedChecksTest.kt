package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * `engagedCheckIds` (the Tutorial's R7 seam, ruling R5). Every update here keeps at least one check
 * engaged, so `isEnabled` never flips and the service never touches the display — which is what
 * lets this run headless without the workbench mocks of `CodeSuggestionsStateServiceTest`.
 */
class CodeSuggestionsStateServiceEngagedChecksTest : DescribeSpec({
  describe("engagedCheckIds") {
    it("is empty before any feature state has arrived") {
      CodeSuggestionsStateService().engagedCheckIds().shouldBeEmpty()
    }

    it("lists every engaged check id in the server's order, skipping the disengaged ones") {
      val service = CodeSuggestionsStateService()
      service.update(
        FeatureStateChange(
          "code_suggestions",
          listOf(
            FeatureStateChangeCheck("code-suggestions-document-unsupported-language", engaged = true),
            FeatureStateChangeCheck("authentication-required", engaged = false),
            FeatureStateChangeCheck("code-suggestions-no-license", engaged = true),
          ),
        ),
      )

      service.engagedCheckIds() shouldContainExactly
        listOf("code-suggestions-document-unsupported-language", "code-suggestions-no-license")
      service.isEnabled shouldBe false
    }
  }
})
