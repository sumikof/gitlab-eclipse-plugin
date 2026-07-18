package com.gitlab.eclipse.suggestions.ui;

import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;

import com.gitlab.eclipse.lsp.CancelStreamingParams;
import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider;
import com.gitlab.eclipse.lsp.InlineCompletionCommand;
import com.gitlab.eclipse.lsp.InlineCompletionContext;
import com.gitlab.eclipse.lsp.InlineCompletionItem;
import com.gitlab.eclipse.lsp.InlineCompletionList;
import com.gitlab.eclipse.lsp.InlineCompletionParams;
import com.gitlab.eclipse.lsp.StreamingCompletionResponse;
import com.gitlab.eclipse.lsp.SuggestionTelemetry;
import com.gitlab.eclipse.suggestions.StreamBuffer;
import com.gitlab.eclipse.suggestions.SuggestionModel;

/**
 * Drives the inline-completion request cycle for one editor: listens for typing,
 * debounces, cancels stale requests, and pushes results into the shared model.
 * All entry points run on the SWT UI thread unless noted.
 */
@SuppressWarnings("restriction") // LSPEclipseUtils: same URI derivation LSP4E uses for didOpen
public final class CompletionSessionManager implements ITextListener, KeyListener, MouseListener {
	private static final int DEBOUNCE_MS = 250;
	private static boolean requestFailureLogged;
	private static IContextActivation suggestionContext;

	private final ITextViewer viewer;
	private CompletableFuture<InlineCompletionList> inflight;
	private int requestSerial;
	private boolean applyingEdit;
	// set when a streaming response was announced; validated when the stream completes
	// volatile: written on the UI thread (onResponse), read from onStreamingNotification
	// which runs on an arbitrary lsp4j notification-dispatch thread
	private volatile int streamOffset;
	private volatile long streamStamp;

	CompletionSessionManager(ITextViewer viewer) {
		this.viewer = viewer;
	}

	void install() {
		viewer.addTextListener(this);
		StyledText widget = viewer.getTextWidget();
		widget.addKeyListener(this);
		widget.addMouseListener(this);
	}

	void dispose() {
		discard();
		viewer.removeTextListener(this);
		StyledText widget = viewer.getTextWidget();
		if (widget != null && !widget.isDisposed()) {
			widget.removeKeyListener(this);
			widget.removeMouseListener(this);
		}
	}

	public ITextViewer viewer() {
		return viewer;
	}

	/** Handlers set this around programmatic document edits so textChanged ignores them. */
	void beginEdit() {
		applyingEdit = true;
	}

	void endEdit() {
		applyingEdit = false;
	}

	@Override
	public void textChanged(TextEvent event) {
		if (applyingEdit || event.getDocumentEvent() == null) {
			return;
		}
		discard();
		scheduleRequest();
	}

	@Override
	public void keyPressed(KeyEvent e) {
		// nothing: dismissal decisions happen on release, once the caret has moved
	}

	@Override
	public void keyReleased(KeyEvent e) {
		switch (e.keyCode) {
		case SWT.ARROW_LEFT, SWT.ARROW_RIGHT, SWT.ARROW_UP, SWT.ARROW_DOWN, SWT.HOME, SWT.END, SWT.PAGE_UP,
				SWT.PAGE_DOWN -> discardIfCaretMoved();
		default -> { /* typing is handled via textChanged */ }
		}
	}

	/**
	 * Navigation keys dismiss the suggestion, unless the caret still sits at the
	 * insertion point. That happens right after a Ctrl+Right word-accept: the key
	 * binding consumes the keyDown, but the keyUp still reaches this listener, and
	 * without this check it would discard the remainder of the suggestion that
	 * the word-accept handler deliberately left showing.
	 */
	private void discardIfCaretMoved() {
		var model = SuggestionSessions.model();
		if (model.isShowing() && viewer.getSelectedRange().x == model.insertionOffset()) {
			return;
		}
		discard();
	}

	@Override
	public void mouseDown(MouseEvent e) {
		discard();
	}

	@Override
	public void mouseUp(MouseEvent e) {
	}

	@Override
	public void mouseDoubleClick(MouseEvent e) {
	}

	private void scheduleRequest() {
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		int serial = ++requestSerial;
		widget.getDisplay().timerExec(DEBOUNCE_MS, () -> {
			if (serial == requestSerial && !widget.isDisposed()) {
				request(InlineCompletionContext.TRIGGER_AUTOMATIC);
			}
		});
	}

	/** Manual trigger: skips the debounce. */
	public void requestNow() {
		++requestSerial; // invalidate any pending debounce timer
		request(InlineCompletionContext.TRIGGER_INVOKED);
	}

	private void request(int triggerKind) {
		var server = GitLabLanguageServerProvider.languageServer;
		IDocument document = viewer.getDocument();
		if (server == null || document == null) {
			return;
		}
		cancelInflight();
		int offset = viewer.getSelectedRange().x;
		InlineCompletionParams params;
		try {
			int line = document.getLineOfOffset(offset);
			int character = offset - document.getLineOffset(line);
			URI uri = LSPEclipseUtils.toUri(document);
			if (uri == null) {
				return;
			}
			params = new InlineCompletionParams(new TextDocumentIdentifier(uri.toString()),
					new Position(line, character), new InlineCompletionContext(triggerKind, null));
		} catch (BadLocationException e) {
			return;
		}
		long stamp = modificationStamp(document);
		CompletableFuture<InlineCompletionList> future = server.inlineCompletion(params);
		inflight = future;
		future.whenComplete((result, error) -> onResponse(result, error, offset, stamp));
	}

	// lsp4j executor thread
	private void onResponse(InlineCompletionList result, Throwable error, int offset, long stamp) {
		if (error != null) {
			logOnce(error);
			return;
		}
		if (result == null || result.items() == null || result.items().isEmpty()) {
			return;
		}
		InlineCompletionItem item = result.items().get(0);
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		widget.getDisplay().asyncExec(() -> {
			if (SuggestionSessions.active() != this) {
				return;
			}
			if (widget.isDisposed() || isStale(offset, stamp)) {
				return;
			}
			InlineCompletionCommand command = item.command();
			if (command != null && InlineCompletionCommand.START_STREAMING.equals(command.command())) {
				streamOffset = offset;
				streamStamp = stamp;
				SuggestionSessions.streamBuffer().start(command.stringArg(0), command.stringArg(1), null);
				return; // 完成テキストは streamingCompletionResponse 通知で届く(Task 10)
			}
			String text = item.insertText();
			if (text == null || text.isEmpty()) {
				return;
			}
			String trackingId = command == null ? null : command.stringArg(0);
			Integer optionIndex = command == null ? null : command.intArg(1);
			showSuggestion(text, offset, trackingId, optionIndex);
		});
	}

	// UI thread
	void showSuggestion(String text, int offset, String trackingId, Integer optionIndex) {
		SuggestionSessions.model().show(new SuggestionModel.Suggestion(text, offset, trackingId, optionIndex));
		SuggestionTelemetry.shown(trackingId, optionIndex);
		activateSuggestionContext();
		refreshMinings();
	}

	// 任意スレッド(lsp4j通知スレッド)から呼ばれる
	void onStreamingNotification(StreamingCompletionResponse response) {
		StreamBuffer.Completed completed = SuggestionSessions.streamBuffer().onNotification(response);
		if (completed == null || completed.text().isEmpty()) {
			return;
		}
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		int offset = streamOffset;
		long stamp = streamStamp;
		widget.getDisplay().asyncExec(() -> {
			if (SuggestionSessions.active() != this) {
				return;
			}
			if (widget.isDisposed() || isStale(offset, stamp)) {
				return;
			}
			showSuggestion(completed.text(), offset, completed.trackingId(), completed.optionIndex());
		});
	}

	/** Clears ghost text and aborts in-flight work (request, stream, pending timer). */
	public void discard() {
		++requestSerial;
		cancelInflight();
		String streamId = SuggestionSessions.streamBuffer().cancel();
		var server = GitLabLanguageServerProvider.languageServer;
		if (streamId != null && server != null) {
			server.cancelStreaming(new CancelStreamingParams(streamId));
		}
		deactivateSuggestionContext();
		if (SuggestionSessions.model().isShowing()) {
			SuggestionSessions.model().clear();
			refreshMinings();
		}
	}

	private static void activateSuggestionContext() {
		if (suggestionContext != null) {
			return;
		}
		var service = PlatformUI.getWorkbench().getService(IContextService.class);
		if (service != null) {
			suggestionContext = service.activateContext("com.gitlab.eclipse.suggestionVisible");
		}
	}

	private static void deactivateSuggestionContext() {
		if (suggestionContext == null) {
			return;
		}
		var service = PlatformUI.getWorkbench().getService(IContextService.class);
		if (service != null) {
			service.deactivateContext(suggestionContext);
		}
		suggestionContext = null;
	}

	void refreshMinings() {
		StyledText widget = viewer.getTextWidget();
		if (widget == null || widget.isDisposed()) {
			return;
		}
		// async: a synchronous call can deadlock against LSP4E's document locks
		widget.getDisplay().asyncExec(() -> {
			if (!widget.isDisposed() && viewer instanceof ISourceViewerExtension5 minings) {
				minings.updateCodeMinings();
			}
		});
	}

	boolean isStale(int offset, long stamp) {
		IDocument document = viewer.getDocument();
		return document == null || modificationStamp(document) != stamp || viewer.getSelectedRange().x != offset;
	}

	private void cancelInflight() {
		if (inflight != null) {
			inflight.cancel(true);
			inflight = null;
		}
	}

	private static long modificationStamp(IDocument document) {
		return document instanceof IDocumentExtension4 extended ? extended.getModificationStamp() : -1;
	}

	private static synchronized void logOnce(Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		if (cause instanceof CancellationException) {
			return; // 正常系: 自分でキャンセルした
		}
		if (requestFailureLogged) {
			return;
		}
		requestFailureLogged = true;
		ILog log = Platform.getLog(CompletionSessionManager.class);
		log.warn("Inline completion request failed (further failures will not be logged)", cause);
	}
}
