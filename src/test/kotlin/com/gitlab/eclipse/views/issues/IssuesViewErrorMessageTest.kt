package com.gitlab.eclipse.views.issues

import com.gitlab.eclipse.api.GitLabConfigurationException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class IssuesViewErrorMessageTest : DescribeSpec({
  it("returns the safe message for a configuration error") {
    configErrorMessage(GitLabConfigurationException("Both cert and key must be set.")) shouldBe
      "Both cert and key must be set."
  }
  it("returns null for a generic error (falls back to the fixed text)") {
    configErrorMessage(RuntimeException("boom")).shouldBeNull()
  }
})
