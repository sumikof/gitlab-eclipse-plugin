package com.gitlab.eclipse.suggestions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

/**
 * Static dispatch point decoupling the LSP client (which receives
 * 'streamingCompletionResponse' notifications) from UI subscribers.
 * Pure JDK: no OSGi/Eclipse dependencies.
 */
public final class StreamingCompletionEvents {
	private static final List<Consumer<StreamingCompletionResponse>> LISTENERS = new CopyOnWriteArrayList<>();

	private StreamingCompletionEvents() {}

	public static void subscribe(Consumer<StreamingCompletionResponse> listener) {
		LISTENERS.add(listener);
	}

	public static void unsubscribe(Consumer<StreamingCompletionResponse> listener) {
		LISTENERS.remove(listener);
	}

	public static void publish(StreamingCompletionResponse response) {
		for (Consumer<StreamingCompletionResponse> listener : LISTENERS) {
			try {
				listener.accept(response);
			} catch (RuntimeException e) {
				// one broken listener must not starve the rest
			}
		}
	}
}
