package com.gitlab.eclipse.telemetry

import com.gitlab.eclipse.codesuggestions.CodeSuggestion
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.telemetry.params.TelemetryAction
import com.gitlab.eclipse.telemetry.params.TelemetryContext
import com.gitlab.eclipse.telemetry.params.TelemetryParams
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class TelemetryServiceTest : DescribeSpec({
  val gitLabLanguageServerWrapper = mockk<GitLabLanguageServerWrapper>(relaxed = true)
  val gitLabLanguageServer = mockk<GitLabLanguageServer>(relaxed = true)
  val telemetryService = TelemetryService(gitLabLanguageServerWrapper)

  val trackingId = "foo"
  val optionId = 123

  every { gitLabLanguageServerWrapper.languageServer } returns gitLabLanguageServer

  it("should send correct telemetry for accepted suggestion") {
    telemetryService.send(
      CodeSuggestion(trackingId, optionId, "Some code suggestion"),
      TelemetryAction.SUGGESTION_ACCEPTED
    )

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = TelemetryAction.SUGGESTION_ACCEPTED.value,
          context = TelemetryContext(trackingId, optionId)
        )
      )
    }
  }

  it("should send correct telemetry for cancelled suggestion") {
    telemetryService.send(
      CodeSuggestion(trackingId, null, "Some code suggestion"),
      TelemetryAction.SUGGESTION_CANCELLED
    )

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = TelemetryAction.SUGGESTION_CANCELLED.value,
          context = TelemetryContext(trackingId)
        )
      )
    }
  }

  it("should send correct telemetry for rejected suggestion") {
    telemetryService.send(
      CodeSuggestion(trackingId, null, "Some code suggestion"),
      TelemetryAction.SUGGESTION_REJECTED
    )

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = TelemetryAction.SUGGESTION_REJECTED.value,
          context = TelemetryContext(trackingId)
        )
      )
    }
  }
})
