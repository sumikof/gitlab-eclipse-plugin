package com.gitlab.eclipse.telemetry

sealed class TelemetryAction(
    val action: String,
    val context: TelemetryContext,
    val category: String = "code_suggestions"
) {

    override fun toString() = "{category:'$category',action:'$action',context:$context}"

    // List of telemetry actions: https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/blob/main/docs/telemetry.md#telemetry-actions

    // The following actions can be handled by the language client (i.e. this Eclipse extension). The rest are handled
    // by the language server for us.

    class Accepted(trackingId: String, val optionId: Int) :
        TelemetryAction("suggestion_accepted", TelemetryContext(trackingId, optionId))

    class Cancelled(trackingId: String) : TelemetryAction("suggestion_cancelled", TelemetryContext(trackingId))

    class NotProvided(trackingId: String) :
        TelemetryAction("suggestion_not_provided", TelemetryContext(trackingId))

    class Shown(trackingId: String) : TelemetryAction("suggestion_shown", TelemetryContext(trackingId))

    class Rejected(trackingId: String) : TelemetryAction("suggestion_rejected", TelemetryContext(trackingId))
}