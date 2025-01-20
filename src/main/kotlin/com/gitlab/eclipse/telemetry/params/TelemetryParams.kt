package com.gitlab.eclipse.telemetry.params

data class TelemetryParams(
  val action: String,
  val context: TelemetryContext,
) {
  // Only code suggestions telemetry is supported for now
  val category: String = "code_suggestions"
}
