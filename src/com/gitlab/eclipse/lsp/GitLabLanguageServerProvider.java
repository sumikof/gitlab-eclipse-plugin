package com.gitlab.eclipse.lsp;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.CodeCompletion;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.FeatureFlags;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.Telemetry;
import com.gitlab.eclipse.preferences.PreferenceConstants;
import com.gitlab.eclipse.preferences.PreferenceInitializer;
import com.gitlab.eclipse.preferences.SecretStringFieldEditor;
import com.gitlab.eclipse.storage.SecretStorage;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;


public class GitLabLanguageServerProvider extends ProcessStreamConnectionProvider implements StreamConnectionProvider {
	public static GitLabLanguageServer languageServer;

	// TODO: Extract into a service which registers the language server.
	public static void onDidChangeConfiguration() {
		if (GitLabLanguageServerProvider.languageServer == null) {
			return;
		}
		
		var preferenceStore = PreferenceInitializer.PREFERENCE_STORE;
		var params = GitLabLanguageServerConfigurationParams.builder()
				.baseUrl(preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL))
				.codeCompletion(new CodeCompletion(true, List.of(), List.of()))
				.featureFlags(FeatureFlags.builder()
						.remoteSecurityScans(false)
						.streamCodeGenerations(preferenceStore.getBoolean(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS))
						.build())
				.ignoreCertificateErrors(preferenceStore.getBoolean(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS))
				.logLevel(preferenceStore.getString(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL))
				.telemetry(new Telemetry(
					preferenceStore.getBoolean(PreferenceConstants.TELEMETRY_ENABLED),
					"https://snowplowprd.trx.gitlab.net")
				);

		var secretStorage = new SecretStorage("gitlab.com");
		var personalAccessToken = secretStorage.getSecret("personal_access_token");
		if (personalAccessToken != null) {
			params.token(personalAccessToken);
		}

		GitLabLanguageServerProvider.languageServer.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(params.build()));
	}

	public GitLabLanguageServerProvider() {
		// TODO: Support configurable language server binary.
		setCommands(List.of(
			"/Users/erran/gitlab-org/editor-extensions/gitlab-lsp/bin/gitlab-lsp-macos-arm64",
			"--stdio"
		));
		// TODO: Make this workspace based.
		setWorkingDirectory("/Users/erran/eclipse-workspace/gitlab-eclipse-plugin/lsp-sandbox");
	}
	
	@Override
	public void handleMessage(Message message, LanguageServer languageServer, URI rootURI) {
		if (GitLabLanguageServerProvider.languageServer == null && languageServer instanceof GitLabLanguageServer lsp) {
			GitLabLanguageServerProvider.languageServer = lsp;
		}

//		System.out.println("message: " + message);
		if (message instanceof NotificationMessage request) {
			switch (request.getMethod()) {
				case "initialized":
					// TODO: Trigger through initialized rpc method?
					onDidChangeConfiguration();
					return;
				default:
					break;
			}
		}

		super.handleMessage(message, languageServer, rootURI);
	}

	@Override
	public Object getInitializationOptions(URI rootUri) {
		return Map.of(
			"extension", Map.of(
				"name", "gitlab-eclipse-plugin",
				"version", "0.1.0-erran"
			),
			"ide", Map.of(
				"name", "gitlab-eclipse-plugin",
				"vendor", "GitLab",
				"version", "0.1.0-erran"
			),
			"folders", List.of(rootUri.toString())
		);
	}
}
