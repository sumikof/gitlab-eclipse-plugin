package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.ICodeMiningProvider;
import org.eclipse.jface.text.codemining.LineContentCodeMining;

/** Grey inline text drawn at the caret. Position length must be >= 1 (platform quirk). */
class InlineGhostTextMining extends LineContentCodeMining {
	InlineGhostTextMining(Position position, ICodeMiningProvider provider, String label) {
		super(position, provider);
		setLabel(label);
	}

	@Override
	public boolean isAfterPosition() {
		return true;
	}
}
