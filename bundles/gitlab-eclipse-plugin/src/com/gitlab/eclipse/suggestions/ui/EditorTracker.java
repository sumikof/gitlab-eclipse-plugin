package com.gitlab.eclipse.suggestions.ui;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.texteditor.ITextEditor;

import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider;

/** Attaches a {@link CompletionSessionManager} to whichever text editor is active. */
@SuppressWarnings("restriction")
public final class EditorTracker implements IPartListener2 {
	private final Map<ITextEditor, CompletionSessionManager> managers = new HashMap<>();

	@Override
	public void partActivated(IWorkbenchPartReference partRef) {
		activatePart(partRef.getPart(false));
	}

	@Override
	public void partBroughtToTop(IWorkbenchPartReference partRef) {
		activatePart(partRef.getPart(false));
	}

	@Override
	public void partClosed(IWorkbenchPartReference partRef) {
		IWorkbenchPart part = partRef.getPart(false);
		ITextEditor editor = Adapters.adapt(part, ITextEditor.class);
		if (editor == null) {
			return;
		}
		CompletionSessionManager manager = managers.remove(editor);
		if (manager != null) {
			if (SuggestionSessions.active() == manager) {
				SuggestionSessions.setActive(null);
			}
			manager.dispose();
		}
	}

	public void activatePart(IWorkbenchPart part) {
		ITextEditor editor = Adapters.adapt(part, ITextEditor.class);
		if (editor == null) {
			return;
		}
		ITextViewer viewer = editor.getAdapter(ITextViewer.class);
		if (viewer == null || viewer.getTextWidget() == null) {
			return;
		}
		CompletionSessionManager manager = managers.computeIfAbsent(editor, e -> {
			CompletionSessionManager created = new CompletionSessionManager(viewer);
			created.install();
			return created;
		});
		CompletionSessionManager previous = SuggestionSessions.active();
		if (previous != null && previous != manager) {
			previous.discard();
		}
		SuggestionSessions.setActive(manager);
		notifyActiveDocument(viewer);
	}

	private static void notifyActiveDocument(ITextViewer viewer) {
		var server = GitLabLanguageServerProvider.languageServer;
		IDocument document = viewer.getDocument();
		if (server == null || document == null) {
			return;
		}
		URI uri = LSPEclipseUtils.toUri(document);
		if (uri != null) {
			server.didChangeDocumentInActiveEditor(uri.toString());
		}
	}
}
