package com.gitlab.eclipse.lsp;

import java.util.List;

import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

/**
 * Command attached to an inline-completion item. gitlab-lsp uses it to carry
 * tracking ids; argument values may arrive as raw JSON primitives depending on
 * the deserializer, so the accessors normalize both representations.
 */
public record InlineCompletionCommand(String title, String command, List<Object> arguments) {
	public static final String START_STREAMING = "gitlab.ls.startStreaming";
	public static final String SUGGESTION_ACCEPTED = "gitlab.ls.codeSuggestionAccepted";

	public String stringArg(int index) {
		Object value = raw(index);
		if (value == null) {
			return null;
		}
		if (value instanceof JsonPrimitive primitive) {
			return primitive.getAsString();
		}
		return value.toString();
	}

	public Integer intArg(int index) {
		Object value = raw(index);
		if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
			return primitive.getAsInt();
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private Object raw(int index) {
		if (arguments == null || index < 0 || index >= arguments.size()) {
			return null;
		}
		Object value = arguments.get(index);
		return value instanceof JsonNull ? null : value;
	}
}
