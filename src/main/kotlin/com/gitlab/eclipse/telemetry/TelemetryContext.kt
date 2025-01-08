package com.gitlab.eclipse.telemetry

data class TelemetryContext(
  val trackingId: String,
  val optionId: Int? = null
) {
  override fun toString() = buildString {
    append("{trackingId:'$trackingId'")

    optionId?.let {
      append(",optionId:$optionId")
    }
  }
}
