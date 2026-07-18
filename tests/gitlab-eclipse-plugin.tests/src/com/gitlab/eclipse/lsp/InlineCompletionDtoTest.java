package com.gitlab.eclipse.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

class InlineCompletionDtoTest {
	private final Gson gson = new Gson();

	@Test
	void serializesParamsToLspShape() {
		var params = new InlineCompletionParams(
				new TextDocumentIdentifier("file:///tmp/A.java"),
				new Position(3, 7),
				new InlineCompletionContext(InlineCompletionContext.TRIGGER_AUTOMATIC, null));
		JsonObject json = gson.toJsonTree(params).getAsJsonObject();
		assertEquals("file:///tmp/A.java", json.getAsJsonObject("textDocument").get("uri").getAsString());
		assertEquals(3, json.getAsJsonObject("position").get("line").getAsInt());
		assertEquals(7, json.getAsJsonObject("position").get("character").getAsInt());
		assertEquals(2, json.getAsJsonObject("context").get("triggerKind").getAsInt());
	}

	@Test
	void deserializesNormalItemWithAcceptedCommand() {
		String json = """
				{"items":[{"insertText":"return x;","range":{"start":{"line":1,"character":2},
				 "end":{"line":1,"character":2}},"command":{"title":"accepted",
				 "command":"gitlab.ls.codeSuggestionAccepted","arguments":["tracking-1",2]}}]}""";
		var list = gson.fromJson(json, InlineCompletionList.class);
		var item = list.items().get(0);
		assertEquals("return x;", item.insertText());
		assertEquals(InlineCompletionCommand.SUGGESTION_ACCEPTED, item.command().command());
		assertEquals("tracking-1", item.command().stringArg(0));
		assertEquals(Integer.valueOf(2), item.command().intArg(1)); // gsonは数値をDoubleで持つ
		assertNull(item.command().intArg(5));                       // 範囲外はnull
	}

	@Test
	void deserializesStreamingItem() {
		String json = """
				{"items":[{"insertText":"","command":{"title":"Start streaming",
				 "command":"gitlab.ls.startStreaming","arguments":["stream-1","tracking-1"]}}]}""";
		var item = gson.fromJson(json, InlineCompletionList.class).items().get(0);
		assertEquals(InlineCompletionCommand.START_STREAMING, item.command().command());
		assertEquals("stream-1", item.command().stringArg(0));
		assertEquals("tracking-1", item.command().stringArg(1));
		assertNull(item.range());
	}

	@Test
	void streamingResponseAllowsMissingCompletion() {
		var response = gson.fromJson("{\"id\":\"s1\",\"done\":true}", StreamingCompletionResponse.class);
		assertEquals("s1", response.id());
		assertNull(response.completion());
		assertTrue(response.done());
	}

	@Test
	void telemetryFactoryBuildsCodeSuggestionEvent() {
		var params = TelemetryParams.codeSuggestion(TelemetryParams.ACTION_SHOWN, "tracking-1", 2);
		assertEquals("code_suggestions", params.category());
		assertEquals("suggestion_shown", params.action());
		assertEquals("tracking-1", params.context().trackingId());
		assertEquals(Integer.valueOf(2), params.context().optionId());
		JsonObject json = gson.toJsonTree(params).getAsJsonObject();
		assertEquals("code_suggestions", json.get("category").getAsString());
	}
}
