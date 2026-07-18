package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.lsp.StreamingCompletionResponse;

class StreamBufferTest {
	private final StreamBuffer buffer = new StreamBuffer();

	@Test
	void accumulatesCumulativeChunksUntilDone() {
		buffer.start("s1", "t1", 2);
		assertTrue(buffer.isActive());
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "hel", false)));
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "hello", false)));
		var completed = buffer.onNotification(new StreamingCompletionResponse("s1", null, true));
		assertEquals("hello", completed.text()); // done通知はcompletion無し=最後の累積値を使う
		assertEquals("t1", completed.trackingId());
		assertEquals(Integer.valueOf(2), completed.optionIndex());
		assertFalse(buffer.isActive());
	}

	@Test
	void ignoresNotificationsForOtherStreams() {
		buffer.start("s1", "t1", null);
		assertNull(buffer.onNotification(new StreamingCompletionResponse("other", "xxx", true)));
		assertTrue(buffer.isActive());
	}

	@Test
	void ignoresNotificationsWhenIdle() {
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "x", true)));
	}

	@Test
	void cancelReturnsIdOnceAndDropsLaterChunks() {
		buffer.start("s1", "t1", null);
		assertEquals("s1", buffer.cancel());
		assertNull(buffer.cancel());
		assertNull(buffer.onNotification(new StreamingCompletionResponse("s1", "late", true)));
	}

	@Test
	void doneWithoutAnyChunkCompletesEmpty() {
		buffer.start("s1", "t1", null);
		assertEquals("", buffer.onNotification(new StreamingCompletionResponse("s1", null, true)).text());
	}
}
