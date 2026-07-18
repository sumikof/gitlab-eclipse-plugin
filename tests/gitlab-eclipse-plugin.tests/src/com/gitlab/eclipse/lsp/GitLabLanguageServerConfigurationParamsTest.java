package com.gitlab.eclipse.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.FeatureFlags;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.Telemetry;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.TelemetryAction;

class GitLabLanguageServerConfigurationParamsTest {
	@Test
	void telemetrySetterMustNotTouchFeatureFlags() {
		var telemetry = new Telemetry(true, "https://snowplow.example",
				List.of(new TelemetryAction("suggestion_shown")));
		var params = GitLabLanguageServerConfigurationParams.builder()
				.telemetry(telemetry)
				.build();
		assertEquals(telemetry, params.telemetry());
		assertNull(params.featureFlags());
	}

	@Test
	void telemetryCarriesSubscribedActions() {
		var telemetry = new Telemetry(true, "https://snowplow.example",
				List.of(new TelemetryAction("suggestion_shown"), new TelemetryAction("suggestion_accepted")));
		assertEquals("suggestion_shown", telemetry.actions().get(0).action());
		assertEquals(2, telemetry.actions().size());
	}

	@Test
	void featureFlagsSetterSetsFeatureFlags() {
		var flags = FeatureFlags.builder().streamCodeGenerations(true).build();
		var params = GitLabLanguageServerConfigurationParams.builder()
				.featureFlags(flags)
				.build();
		assertEquals(flags, params.featureFlags());
		assertNull(params.telemetry());
	}
}
