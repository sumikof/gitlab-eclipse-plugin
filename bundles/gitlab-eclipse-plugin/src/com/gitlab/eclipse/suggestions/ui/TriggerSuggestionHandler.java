package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/** Ctrl/Cmd+Alt+/: requests a suggestion immediately, skipping the debounce. */
public class TriggerSuggestionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		CompletionSessionManager manager = SuggestionSessions.active();
		if (manager != null) {
			manager.requestNow();
		}
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.active() != null;
	}
}
