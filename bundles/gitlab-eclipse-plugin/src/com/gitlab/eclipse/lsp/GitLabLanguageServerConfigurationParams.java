package com.gitlab.eclipse.lsp;

import java.util.List;

public record GitLabLanguageServerConfigurationParams
(
	String baseUrl,
	String logLevel,
	GitLabLanguageServerConfigurationParams.Telemetry telemetry,
	String token,
	GitLabLanguageServerConfigurationParams.CodeCompletion codeCompletion,
	GitLabLanguageServerConfigurationParams.FeatureFlags featureFlags,
	boolean ignoreCertificateErrors
	// String projectPath,
) {
	public static class Builder {
		private String baseUrl;
		private String logLevel;
		private GitLabLanguageServerConfigurationParams.CodeCompletion codeCompletion;
		private GitLabLanguageServerConfigurationParams.FeatureFlags featureFlags;
		private GitLabLanguageServerConfigurationParams.Telemetry telemetry;
		private String token;
		private boolean ignoreCertificateErrors;

		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder codeCompletion(GitLabLanguageServerConfigurationParams.CodeCompletion codeCompletion) {
			this.codeCompletion = codeCompletion;
			return this;
		}

		public Builder featureFlags(FeatureFlags featureFlags) {
			this.featureFlags = featureFlags;
			return this;
		}

		public Builder ignoreCertificateErrors(boolean ignoreCertificateErrors) {
			this.ignoreCertificateErrors = ignoreCertificateErrors;
			return this;
		}

		public Builder logLevel(String logLevel) {
			this.logLevel = logLevel;
			return this;
		}

		public Builder telemetry(GitLabLanguageServerConfigurationParams.Telemetry telemetry) {
			this.telemetry = telemetry;
			return this;
		}

		public Builder token(String token) {
			this.token = token;
			return this;
		}

		public GitLabLanguageServerConfigurationParams build() {
			return new GitLabLanguageServerConfigurationParams(baseUrl, logLevel, telemetry, token, codeCompletion, featureFlags, ignoreCertificateErrors);
		}
	}

	public static record CodeCompletion(boolean enableSecretRedaction, List<String> disabledSupportedLanguages, List<String> additionalLanguages) {}
	
	public static record FeatureFlags(boolean remoteSecurityScans, boolean streamCodeGenerations) {
		public static class Builder {
			boolean remoteSecurityScans;
			boolean streamCodeGenerations;

			public Builder remoteSecurityScans(boolean remoteSecurityScans) {
				this.remoteSecurityScans = remoteSecurityScans;
				return this;
			}

			public Builder streamCodeGenerations(boolean streamCodeGenerations) {
				this.streamCodeGenerations = streamCodeGenerations;
				return this;
			}

			public FeatureFlags build() {
				return new FeatureFlags(remoteSecurityScans, streamCodeGenerations);
			}
		}

		public static Builder builder() {
			return new Builder();
		}
	}

	public static record HttpAgentOptions(String ca, String cert, String certKey) {}

	public static record Telemetry(boolean enabled, String trackingUrl, List<TelemetryAction> actions) {}

	public static record TelemetryAction(String action) {}

	public static GitLabLanguageServerConfigurationParams.Builder builder() {
		return new Builder();
	}
}
