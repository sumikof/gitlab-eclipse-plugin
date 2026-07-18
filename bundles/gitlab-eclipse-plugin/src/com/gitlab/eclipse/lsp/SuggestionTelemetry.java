package com.gitlab.eclipse.lsp;

/**
 * Sends code-suggestion telemetry to the language server. Because we subscribe
 * to shown/accepted in the didChangeConfiguration telemetry.actions, the server
 * relies on the client to report these events.
 */
public final class SuggestionTelemetry {
	private SuggestionTelemetry() {}

	public static void shown(String trackingId, Integer optionIndex) {
		send(TelemetryParams.ACTION_SHOWN, trackingId, optionIndex);
	}

	public static void accepted(String trackingId, Integer optionIndex) {
		send(TelemetryParams.ACTION_ACCEPTED, trackingId, optionIndex);
	}

	private static void send(String action, String trackingId, Integer optionIndex) {
		GitLabLanguageServer server = GitLabLanguageServerProvider.languageServer;
		if (server == null || trackingId == null) {
			return;
		}
		server.telemetry(TelemetryParams.codeSuggestion(action, trackingId, optionIndex));
	}
}
