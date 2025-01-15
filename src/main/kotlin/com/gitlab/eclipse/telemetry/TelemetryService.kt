package com.gitlab.eclipse.telemetry

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.telemetry.params.TelemetryContext
import com.gitlab.eclipse.telemetry.params.TelemetryParams

class TelemetryService(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper
) {

  private val gitLabLanguageServer get() = gitLabLanguageServerWrapper.languageServer

  // The following code suggestions telemetry can be handled by the language client (i.e. this Eclipse extension).
  // The rest are handled by the language server for us.
  // [Reference](https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/blob/main/docs/telemetry.md)

  fun sendCodeSuggestionAcceptedTelemetry(trackingId: String, optionId: Int) {
    gitLabLanguageServer?.telemetry(
      TelemetryParams(
        action = "suggestion_accepted",
        context = TelemetryContext(trackingId, optionId)
      )
    )
  }

  fun sendCodeSuggestionNotProvidedTelemetry(trackingId: String) {
    gitLabLanguageServer?.telemetry(
      TelemetryParams(
        action = "suggestion_not_provided",
        context = TelemetryContext(trackingId)
      )
    )
  }

  fun sendCodeSuggestionCancelledTelemetry(trackingId: String) {
    gitLabLanguageServer?.telemetry(
      TelemetryParams(
        action = "suggestion_cancelled",
        context = TelemetryContext(trackingId)
      )
    )
  }

  fun sendCodeSuggestionRejectedTelemetry(trackingId: String) {
    gitLabLanguageServer?.telemetry(
      TelemetryParams(
        action = "suggestion_rejected",
        context = TelemetryContext(trackingId)
      )
    )
  }

  fun sendCodeSuggestionShownTelemetry(trackingId: String) {
    gitLabLanguageServer?.telemetry(
      TelemetryParams(
        action = "suggestion_shown",
        context = TelemetryContext(trackingId)
      )
    )
  }
}
