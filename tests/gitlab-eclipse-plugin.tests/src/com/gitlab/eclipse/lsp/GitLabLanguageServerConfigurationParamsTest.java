package com.gitlab.eclipse.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.FeatureFlags;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.Telemetry;

class GitLabLanguageServerConfigurationParamsTest {
	@Test
	void telemetrySetterMustNotTouchFeatureFlags() {
		var telemetry = new Telemetry(true, "https://snowplow.example");
		var params = GitLabLanguageServerConfigurationParams.builder()
				.telemetry(telemetry)
				.build();
		assertEquals(telemetry, params.telemetry());
		assertNull(params.featureFlags());
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
