package com.gitlab.eclipse.lsp;

/** gitlab-lsp '$/gitlab/telemetry' notification params. */
public record TelemetryParams(String category, String action, CodeSuggestionsContext context) {
	public static final String CATEGORY_CODE_SUGGESTIONS = "code_suggestions";
	public static final String ACTION_SHOWN = "suggestion_shown";
	public static final String ACTION_ACCEPTED = "suggestion_accepted";

	public record CodeSuggestionsContext(String trackingId, Integer optionId) {}

	public static TelemetryParams codeSuggestion(String action, String trackingId, Integer optionId) {
		return new TelemetryParams(CATEGORY_CODE_SUGGESTIONS, action, new CodeSuggestionsContext(trackingId, optionId));
	}
}
