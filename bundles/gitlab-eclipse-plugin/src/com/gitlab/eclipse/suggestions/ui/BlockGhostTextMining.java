package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;

/** Multi-line grey block rendered above the line after the caret. Label may contain newlines. */
class BlockGhostTextMining extends LineHeaderCodeMining {
	BlockGhostTextMining(int beforeLineNumber, IDocument document, ICodeMiningProvider provider, String label)
			throws BadLocationException {
		super(beforeLineNumber, document, provider);
		setLabel(label);
	}
}
