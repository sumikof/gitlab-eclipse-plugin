package com.gitlab.eclipse.lsp.install;

import java.nio.file.Path;

import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IStartup;
import org.osgi.framework.FrameworkUtil;

import com.gitlab.eclipse.preferences.PreferenceConstants;
import com.gitlab.eclipse.preferences.PreferenceInitializer;

/** Kicks off the language-server download at workbench start when it is not cached yet. */
public class LanguageServerStartup implements IStartup {

	public static LanguageServerInstaller defaultInstaller() {
		Path stateLocation = Platform.getStateLocation(FrameworkUtil.getBundle(LanguageServerStartup.class)).toPath();
		return new LanguageServerInstaller(stateLocation,
				LanguageServerPlatform.detect(System.getProperty("os.name"), System.getProperty("os.arch")));
	}

	@Override
	public void earlyStartup() {
		String configured = PreferenceInitializer.PREFERENCE_STORE
				.getString(PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH);
		if (!configured.isBlank()) {
			return;
		}
		LanguageServerInstaller installer = defaultInstaller();
		if (!installer.isInstalled()) {
			new LanguageServerInstallJob(installer).schedule();
		}
	}
}
