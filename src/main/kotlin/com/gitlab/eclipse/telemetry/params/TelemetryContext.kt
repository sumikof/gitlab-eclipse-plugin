package com.gitlab.eclipse.telemetry.params

data class TelemetryContext(
  val trackingId: String,
  val optionId: Int? = null // Only used by "suggestion_accepted" telemetry action for now
)
