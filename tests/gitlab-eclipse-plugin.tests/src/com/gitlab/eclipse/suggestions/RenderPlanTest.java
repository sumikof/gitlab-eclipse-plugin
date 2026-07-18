package com.gitlab.eclipse.suggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RenderPlanTest {
	@Test
	void singleLineHasNoBlock() {
		var plan = RenderPlan.of("return x;", 4);
		assertEquals("return x;", plan.firstLine());
		assertNull(plan.block());
	}

	@Test
	void splitsFirstLineFromBlock() {
		var plan = RenderPlan.of("if (x) {\n\treturn;\n}", 4);
		assertEquals("if (x) {", plan.firstLine());
		assertEquals("    return;\n}", plan.block());
	}

	@Test
	void expandsOnlyLeadingTabs() {
		var plan = RenderPlan.of("\ta\tb\n\t\tc", 2);
		assertEquals("  a\tb", plan.firstLine()); // 行中のタブは温存
		assertEquals("    c", plan.block());
	}

	@Test
	void normalizesCrlf() {
		var plan = RenderPlan.of("a\r\nb\rc", 4);
		assertEquals("a", plan.firstLine());
		assertEquals("b\nc", plan.block());
	}

	@Test
	void trailingNewlineOnlyMeansNoBlock() {
		assertNull(RenderPlan.of("a\n", 4).block());
	}

	@Test
	void blankInputYieldsNull() {
		assertNull(RenderPlan.of("", 4));
		assertNull(RenderPlan.of(null, 4));
	}
}
