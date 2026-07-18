package com.gitlab.eclipse.suggestions;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

/**
 * Accumulates gitlab-lsp streaming notifications for the active stream.
 * The server sends cumulative text, so only the latest chunk is kept.
 * Thread-safe. Pure JDK: no OSGi/Eclipse dependencies.
 */
public final class StreamBuffer {
	public record Completed(String text, String trackingId, Integer optionIndex) {}

	private String streamId;
	private String trackingId;
	private Integer optionIndex;
	private String latest = "";

	public synchronized void start(String streamId, String trackingId, Integer optionIndex) {
		this.streamId = streamId;
		this.trackingId = trackingId;
		this.optionIndex = optionIndex;
		this.latest = "";
	}

	public synchronized boolean isActive() {
		return streamId != null;
	}

	/** Returns the finished suggestion when this notification completes the active stream, else null. */
	public synchronized Completed onNotification(StreamingCompletionResponse response) {
		if (streamId == null || !streamId.equals(response.id())) {
			return null;
		}
		if (response.completion() != null) {
			latest = response.completion();
		}
		if (!response.done()) {
			return null;
		}
		Completed completed = new Completed(latest, trackingId, optionIndex);
		reset();
		return completed;
	}

	/** Clears the buffer; returns the id to send 'cancelStreaming' for, or null when idle. */
	public synchronized String cancel() {
		String id = streamId;
		reset();
		return id;
	}

	private void reset() {
		streamId = null;
		trackingId = null;
		optionIndex = null;
		latest = "";
	}
}
