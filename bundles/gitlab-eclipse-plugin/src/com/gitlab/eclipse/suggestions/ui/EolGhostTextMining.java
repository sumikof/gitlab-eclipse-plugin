package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineEndCodeMining;

/** Grey text appended at the end of the caret line. */
class EolGhostTextMining extends LineEndCodeMining {
	EolGhostTextMining(IDocument document, int line, ICodeMiningProvider provider, String label)
			throws BadLocationException {
		super(document, line, provider);
		setLabel(label);
	}

	@Override
	public boolean isAfterPosition() {
		return true;
	}
}
