package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.suggestions.SuggestionModel.Suggestion;

class SuggestionModelTest {
	private final SuggestionModel model = new SuggestionModel();

	@Test
	void showClearLifecycle() {
		assertFalse(model.isShowing());
		assertNull(model.remainingText());
		model.show(new Suggestion("abc", 10, "t1", null));
		assertTrue(model.isShowing());
		assertEquals("abc", model.remainingText());
		assertEquals(10, model.insertionOffset());
		model.clear();
		assertFalse(model.isShowing());
		assertNull(model.remainingText());
	}

	@Test
	void nextChunkSplitsWordsWhitespaceAndSymbols() {
		model.show(new Suggestion("foo bar();", 0, "t1", null));
		assertEquals("foo", model.nextChunk());
		model.advance(3);
		assertEquals(" bar", model.nextChunk());
		model.advance(4);
		assertEquals("(", model.nextChunk());
		model.advance(1);
		assertEquals(")", model.nextChunk());
		model.advance(1);
		assertEquals(";", model.nextChunk());
	}

	@Test
	void nextChunkCarriesNewlineIndentIntoNextWord() {
		model.show(new Suggestion("a\n\tb", 0, "t1", null));
		assertEquals("a", model.nextChunk());
		model.advance(1);
		assertEquals("\n\tb", model.nextChunk());
	}

	@Test
	void nextChunkReturnsTrailingWhitespaceWhole() {
		model.show(new Suggestion("a  ", 0, "t1", null));
		model.advance(1);
		assertEquals("  ", model.nextChunk());
	}

	@Test
	void advanceClearsWhenFullyConsumed() {
		model.show(new Suggestion("ab", 5, "t1", 2));
		assertFalse(model.advance(1));
		assertEquals(6, model.insertionOffset());
		assertEquals("b", model.remainingText());
		assertTrue(model.advance(1));
		assertFalse(model.isShowing());
	}
}
