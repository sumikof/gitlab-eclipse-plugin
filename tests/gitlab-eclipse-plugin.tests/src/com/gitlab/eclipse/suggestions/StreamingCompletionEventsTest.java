package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

class StreamingCompletionEventsTest {
	@Test
	void deliversToSubscribersAndStopsAfterUnsubscribe() {
		List<String> seen = new ArrayList<>();
		Consumer<StreamingCompletionResponse> listener = r -> seen.add(r.id());
		StreamingCompletionEvents.subscribe(listener);
		try {
			StreamingCompletionEvents.publish(new StreamingCompletionResponse("a", "x", false));
		} finally {
			StreamingCompletionEvents.unsubscribe(listener);
		}
		StreamingCompletionEvents.publish(new StreamingCompletionResponse("b", "y", true));
		assertEquals(List.of("a"), seen);
	}

	@Test
	void aFailingListenerDoesNotBlockOthers() {
		List<String> seen = new ArrayList<>();
		Consumer<StreamingCompletionResponse> bad = r -> { throw new IllegalStateException("boom"); };
		Consumer<StreamingCompletionResponse> good = r -> seen.add(r.id());
		StreamingCompletionEvents.subscribe(bad);
		StreamingCompletionEvents.subscribe(good);
		try {
			StreamingCompletionEvents.publish(new StreamingCompletionResponse("a", null, true));
		} finally {
			StreamingCompletionEvents.unsubscribe(bad);
			StreamingCompletionEvents.unsubscribe(good);
		}
		assertEquals(List.of("a"), seen);
	}
}
