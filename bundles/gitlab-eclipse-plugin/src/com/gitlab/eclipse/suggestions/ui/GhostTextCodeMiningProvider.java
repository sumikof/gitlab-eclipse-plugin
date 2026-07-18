package com.gitlab.eclipse.suggestions.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;

import com.gitlab.eclipse.suggestions.RenderPlan;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/**
 * Thin rendering layer: converts the shared suggestion model into code minings.
 * All state lives in {@link SuggestionSessions}; this class only draws it.
 */
public class GhostTextCodeMiningProvider extends AbstractCodeMiningProvider {

	@Override
	public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer viewer,
			IProgressMonitor monitor) {
		return CompletableFuture.completedFuture(computeMinings(viewer));
	}

	private List<? extends ICodeMining> computeMinings(ITextViewer viewer) {
		CompletionSessionManager active = SuggestionSessions.active();
		if (active == null || active.viewer() != viewer) {
			return List.of();
		}
		SuggestionModel model = SuggestionSessions.model();
		String remaining = model.remainingText();
		if (remaining == null || remaining.isEmpty()) {
			return List.of();
		}
		IDocument document = viewer.getDocument();
		if (document == null) {
			return List.of();
		}
		RenderPlan plan = RenderPlan.of(remaining, tabSize());
		if (plan == null) {
			return List.of();
		}
		int offset = model.insertionOffset();
		List<ICodeMining> minings = new ArrayList<>(2);
		try {
			int line = document.getLineOfOffset(offset);
			IRegion lineInfo = document.getLineInformation(line);
			int lineEnd = lineInfo.getOffset() + lineInfo.getLength();
			if (!plan.firstLine().isEmpty()) {
				if (offset >= lineEnd) {
					minings.add(new EolGhostTextMining(document, line, this, plan.firstLine()));
				} else {
					minings.add(new InlineGhostTextMining(new Position(offset, 1), this, plan.firstLine()));
				}
			}
			// 既知の制約: 最終行では次行が無いためブロックを表示できない
			if (plan.block() != null && line + 1 < document.getNumberOfLines()) {
				minings.add(new BlockGhostTextMining(line + 1, document, this, plan.block()));
			}
		} catch (BadLocationException e) {
			return List.of();
		}
		return minings;
	}

	private static int tabSize() {
		int size = EditorsUI.getPreferenceStore()
				.getInt(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH);
		return size > 0 ? size : 4;
	}
}
