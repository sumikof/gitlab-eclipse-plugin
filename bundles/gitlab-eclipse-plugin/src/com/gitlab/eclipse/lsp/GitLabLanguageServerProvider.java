package com.gitlab.eclipse.lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.CodeCompletion;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.FeatureFlags;
import com.gitlab.eclipse.lsp.GitLabLanguageServerConfigurationParams.Telemetry;
import com.gitlab.eclipse.lsp.install.LanguageServerStartup;
import com.gitlab.eclipse.preferences.PreferenceConstants;
import com.gitlab.eclipse.preferences.PreferenceInitializer;
import com.gitlab.eclipse.preferences.SecretStringFieldEditor;
import com.gitlab.eclipse.storage.SecretStorage;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.osgi.framework.FrameworkUtil;


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

		var secretStorage = SecretStorage.forConfiguredInstance();
		var personalAccessToken = secretStorage.getSecret("personal_access_token");
		if (personalAccessToken != null) {
			params.token(personalAccessToken);
		}

		GitLabLanguageServerProvider.languageServer.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(params.build()));
	}

	public GitLabLanguageServerProvider() {
		String configured = PreferenceInitializer.PREFERENCE_STORE
				.getString(PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH);
		Path binary = configured.isBlank()
				? LanguageServerStartup.defaultInstaller().binaryPath()
				: Path.of(configured);
		setCommands(List.of(binary.toString(), "--stdio"));

		Path workDir = Platform.getStateLocation(FrameworkUtil.getBundle(getClass())).toPath()
				.resolve("lsp-workdir");
		try {
			Files.createDirectories(workDir);
		} catch (IOException e) {
			// fall back to launching in the default working directory
		}
		setWorkingDirectory(workDir.toString());
	}

	@Override
	public void start() throws IOException {
		Path binary = Path.of(getCommands().get(0));
		if (!Files.isRegularFile(binary)) {
			throw new IOException("GitLab Language Server binary not found at " + binary
					+ ". It may still be downloading (see the Progress view); reopen the file once it completes.");
		}
		super.start();
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
		String version = FrameworkUtil.getBundle(getClass()).getVersion().toString();
		return Map.of(
			"extension", Map.of(
				"name", "gitlab-eclipse-plugin",
				"version", version
			),
			"ide", Map.of(
				"name", "gitlab-eclipse-plugin",
				"vendor", "GitLab",
				"version", version
			),
			"folders", List.of(rootUri.toString())
		);
	}
}
