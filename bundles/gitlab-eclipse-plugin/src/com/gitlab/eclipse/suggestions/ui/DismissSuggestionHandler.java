package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/** ESC: discards the current suggestion (and cancels an active stream). */
public class DismissSuggestionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		CompletionSessionManager manager = SuggestionSessions.active();
		if (manager != null) {
			manager.discard();
		}
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.active() != null
				&& (SuggestionSessions.model().isShowing() || SuggestionSessions.streamBuffer().isActive());
	}
}
