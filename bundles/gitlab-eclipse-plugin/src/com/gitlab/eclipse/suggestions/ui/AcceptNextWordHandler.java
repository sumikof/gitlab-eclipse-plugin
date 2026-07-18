package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import com.gitlab.eclipse.lsp.SuggestionTelemetry;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/** Ctrl/Cmd+Right: inserts the next word-sized chunk of the suggestion. */
public class AcceptNextWordHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		CompletionSessionManager manager = SuggestionSessions.active();
		SuggestionModel model = SuggestionSessions.model();
		SuggestionModel.Suggestion suggestion = model.current();
		String chunk = model.nextChunk();
		if (manager == null || suggestion == null || chunk == null) {
			return null;
		}
		int offset = model.insertionOffset();
		IDocument document = manager.viewer().getDocument();
		if (document == null) {
			return null;
		}
		try {
			manager.beginEdit();
			document.replace(offset, 0, chunk);
			manager.viewer().setSelectedRange(offset + chunk.length(), 0);
		} catch (BadLocationException e) {
			throw new ExecutionException("Failed to insert code suggestion word", e);
		} finally {
			manager.endEdit();
		}
		boolean fullyConsumed = model.advance(chunk.length());
		if (fullyConsumed) {
			SuggestionTelemetry.accepted(suggestion.trackingId(), suggestion.optionIndex());
		}
		manager.refreshMinings();
		return null;
	}

	@Override
	public boolean isEnabled() {
		return SuggestionSessions.model().isShowing() && SuggestionSessions.active() != null;
	}
}
