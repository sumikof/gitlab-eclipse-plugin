package com.gitlab.eclipse.telemetry

object TelemetryService {
    // Returns the string version for now. In the future, this will send telemetry to LS.
    fun track(action: TelemetryAction): String {
        return action.toString()
    }
}