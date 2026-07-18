package com.gitlab.eclipse.suggestions;

/**
 * Splits a suggestion into the caret-line part and the following block, and
 * expands leading tabs (the CodeMining API does not render tab characters).
 * Pure JDK: no OSGi/Eclipse dependencies.
 */
public record RenderPlan(String firstLine, String block) {

	/** Returns null for blank input. tabSize below 1 is treated as 1. */
	public static RenderPlan of(String suggestionText, int tabSize) {
		if (suggestionText == null || suggestionText.isEmpty()) {
			return null;
		}
		String normalized = suggestionText.replace("\r\n", "\n").replace('\r', '\n');
		int newline = normalized.indexOf('\n');
		String first = newline < 0 ? normalized : normalized.substring(0, newline);
		String rest = newline < 0 ? null : normalized.substring(newline + 1);
		if (rest != null) {
			rest = expandLeadingTabs(rest, tabSize);
			if (rest.isEmpty()) {
				rest = null;
			}
		}
		return new RenderPlan(expandLeadingTabs(first, tabSize), rest);
	}

	private static String expandLeadingTabs(String text, int tabSize) {
		String spaces = " ".repeat(Math.max(1, tabSize));
		String[] lines = text.split("\n", -1);
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				out.append('\n');
			}
			String line = lines[i];
			int tabs = 0;
			while (tabs < line.length() && line.charAt(tabs) == '\t') {
				tabs++;
			}
			out.append(spaces.repeat(tabs)).append(line.substring(tabs));
		}
		return out.toString();
	}
}
