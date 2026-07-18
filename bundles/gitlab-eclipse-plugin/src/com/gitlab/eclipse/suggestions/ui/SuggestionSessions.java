package com.gitlab.eclipse.suggestions.ui;

import com.gitlab.eclipse.suggestions.StreamBuffer;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/** Process-wide session state: one suggestion model/stream, one active editor manager. */
public final class SuggestionSessions {
	private static final SuggestionModel MODEL = new SuggestionModel();
	private static final StreamBuffer STREAM = new StreamBuffer();
	private static volatile CompletionSessionManager active;

	private SuggestionSessions() {}

	public static SuggestionModel model() {
		return MODEL;
	}

	public static StreamBuffer streamBuffer() {
		return STREAM;
	}

	public static CompletionSessionManager active() {
		return active;
	}

	static void setActive(CompletionSessionManager manager) {
		active = manager;
	}
}
