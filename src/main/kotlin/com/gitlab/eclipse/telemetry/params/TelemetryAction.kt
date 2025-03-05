package com.gitlab.eclipse.telemetry.params

enum class TelemetryAction(val value: String) {
  SUGGESTION_ACCEPTED("suggestion_accepted"),
  SUGGESTION_CANCELLED("suggestion_cancelled"),
  SUGGESTION_REJECTED("suggestion_rejected"),
  SUGGESTION_NOT_PROVIDED("suggestion_not_provided"),
  SUGGESTION_SHOWN("suggestion_shown")
}
