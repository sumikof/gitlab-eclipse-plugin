package com.gitlab.eclipse.suggestions;

/**
 * Holds the currently displayed inline suggestion and the word-by-word
 * acceptance progress. Thread-safe. Pure JDK: no OSGi/Eclipse dependencies.
 */
public final class SuggestionModel {
	public record Suggestion(String text, int offset, String trackingId, Integer optionIndex) {}

	private Suggestion current;
	private int consumed;

	public synchronized void show(Suggestion suggestion) {
		this.current = suggestion;
		this.consumed = 0;
	}

	public synchronized void clear() {
		current = null;
		consumed = 0;
	}

	public synchronized boolean isShowing() {
		return current != null;
	}

	public synchronized Suggestion current() {
		return current;
	}

	/** Text not yet inserted into the document, or null when nothing is showing. */
	public synchronized String remainingText() {
		return current == null ? null : current.text().substring(consumed);
	}

	/** Document offset where {@link #remainingText()} should be inserted. */
	public synchronized int insertionOffset() {
		return current.offset() + consumed;
	}

	/**
	 * Next word-sized chunk of the remaining text: leading whitespace plus a run
	 * of word characters, or a single symbol. Null when nothing remains.
	 */
	public synchronized String nextChunk() {
		String remaining = remainingText();
		if (remaining == null || remaining.isEmpty()) {
			return null;
		}
		int i = 0;
		while (i < remaining.length() && Character.isWhitespace(remaining.charAt(i))) {
			i++;
		}
		if (i == remaining.length()) {
			return remaining;
		}
		if (isWordChar(remaining.charAt(i))) {
			while (i < remaining.length() && isWordChar(remaining.charAt(i))) {
				i++;
			}
		} else {
			i++;
		}
		return remaining.substring(0, i);
	}

	/** Advances past an accepted chunk. Returns true when fully consumed (and clears). */
	public synchronized boolean advance(int chars) {
		consumed += chars;
		if (current != null && consumed >= current.text().length()) {
			clear();
			return true;
		}
		return false;
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}
}
