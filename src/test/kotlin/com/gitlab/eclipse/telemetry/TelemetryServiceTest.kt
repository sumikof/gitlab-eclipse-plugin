package com.gitlab.eclipse.telemetry

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
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

  val trackingId = "test-tracking-id"
  val optionId = 1

  every { gitLabLanguageServerWrapper.languageServer } returns gitLabLanguageServer

  it("should send correct telemetry for accepted suggestion") {
    telemetryService.sendCodeSuggestionAcceptedTelemetry(trackingId, optionId)

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = "suggestion_accepted",
          context = TelemetryContext(trackingId, optionId)
        )
      )
    }
  }

  it("should send correct telemetry for suggestion not provided") {
    telemetryService.sendCodeSuggestionNotProvidedTelemetry(trackingId)

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = "suggestion_not_provided",
          context = TelemetryContext(trackingId)
        )
      )
    }
  }

  it("should send correct telemetry for cancelled suggestion") {
    telemetryService.sendCodeSuggestionCancelledTelemetry(trackingId)

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = "suggestion_cancelled",
          context = TelemetryContext(trackingId)
        )
      )
    }
  }

  it("should send correct telemetry for rejected suggestion") {
    telemetryService.sendCodeSuggestionRejectedTelemetry(trackingId)

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = "suggestion_rejected",
          context = TelemetryContext(trackingId)
        )
      )
    }
  }

  it("should send correct telemetry for shown suggestion") {
    telemetryService.sendCodeSuggestionShownTelemetry(trackingId)

    verify {
      gitLabLanguageServer.telemetry(
        TelemetryParams(
          action = "suggestion_shown",
          context = TelemetryContext(trackingId)
        )
      )
    }
  }
})
