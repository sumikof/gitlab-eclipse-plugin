package com.gitlab.eclipse.telemetry

import com.gitlab.eclipse.codesuggestions.CodeSuggestion
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.telemetry.params.TelemetryAction
import com.gitlab.eclipse.telemetry.params.TelemetryContext
import com.gitlab.eclipse.telemetry.params.TelemetryParams

internal class TelemetryService(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper
) {

  private val gitLabLanguageServer get() = gitLabLanguageServerWrapper.languageServer

  fun send(codeSuggestion: CodeSuggestion, action: TelemetryAction) {
    val context = when (action) {
      TelemetryAction.SUGGESTION_ACCEPTED -> TelemetryContext(codeSuggestion.trackingId, codeSuggestion.optionId)
      else -> TelemetryContext(codeSuggestion.trackingId)
    }

    gitLabLanguageServer?.telemetry(
      TelemetryParams(
        action = action.value,
        context = context
      )
    )
  }
}
