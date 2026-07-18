package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import com.gitlab.eclipse.lsp.SuggestionTelemetry;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/** TAB: inserts the whole remaining suggestion. */
public class AcceptSuggestionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		CompletionSessionManager manager = SuggestionSessions.active();
		SuggestionModel model = SuggestionSessions.model();
		SuggestionModel.Suggestion suggestion = model.current();
		if (manager == null || suggestion == null) {
			return null;
		}
		String remaining = model.remainingText();
		int offset = model.insertionOffset();
		IDocument document = manager.viewer().getDocument();
		if (document == null) {
			return null;
		}
		try {
			manager.beginEdit();
			document.replace(offset, 0, remaining);
			manager.viewer().setSelectedRange(offset + remaining.length(), 0);
		} catch (BadLocationException e) {
			throw new ExecutionException("Failed to insert code suggestion", e);
		} finally {
			manager.endEdit();
		}
		model.clear();
		SuggestionTelemetry.accepted(suggestion.trackingId(), suggestion.optionIndex());
		manager.refreshMinings();
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.model().isShowing() && SuggestionSessions.active() != null;
	}
}
