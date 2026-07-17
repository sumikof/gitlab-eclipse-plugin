package com.gitlab.eclipse.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

public class PreferenceInitializer extends AbstractPreferenceInitializer {
	public static ScopedPreferenceStore PREFERENCE_STORE = new ScopedPreferenceStore(InstanceScope.INSTANCE, String.valueOf(FrameworkUtil.getBundle(PreferenceInitializer.class).getBundleId()));

	public PreferenceInitializer() {
		this(PREFERENCE_STORE);
	}

	public PreferenceInitializer(ScopedPreferenceStore store) {
		PreferenceInitializer.PREFERENCE_STORE = store;
	}

	@Override
	public void initializeDefaultPreferences() {
		PREFERENCE_STORE.setDefault(PreferenceConstants.GITLAB_INSTANCE_URL, "https://gitlab.com");
		PREFERENCE_STORE.setDefault(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS, false);
		PREFERENCE_STORE.setDefault(PreferenceConstants.TELEMETRY_ENABLED, true);

		// TODO: Set the default lsp log level to "info".
		PREFERENCE_STORE.setDefault(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL, "debug");
		PREFERENCE_STORE.setDefault(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS, true);
		PREFERENCE_STORE.setDefault(PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH, "");

		// Dev tooling
		PREFERENCE_STORE.setDefault(PreferenceConstants.TANUKI_ONLY_SHOW_CODE_MININGS, false);
	}
}
