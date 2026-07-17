package com.gitlab.eclipse.lsp.install;

/**
 * Platforms the GitLab Language Server ships standalone binaries for.
 * Pure JDK: no OSGi/Eclipse dependencies.
 */
public enum LanguageServerPlatform {
	LINUX_X64("linux-x64", false),
	LINUX_ARM64("linux-arm64", false),
	MACOS_X64("macos-x64", false),
	MACOS_ARM64("macos-arm64", false),
	WIN_X64("win-x64", true);

	private final String assetName;
	private final boolean windows;

	LanguageServerPlatform(String assetName, boolean windows) {
		this.assetName = assetName;
		this.windows = windows;
	}

	public String binaryFileName() {
		return "gitlab-lsp-" + assetName + (windows ? ".exe" : "");
	}

	public static LanguageServerPlatform detect(String osName, String osArch) {
		String os = osName.toLowerCase();
		String arch = osArch.toLowerCase();
		boolean arm = arch.equals("aarch64") || arch.equals("arm64");
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		if (os.contains("linux")) {
			if (arm) return LINUX_ARM64;
			if (x64) return LINUX_X64;
		} else if (os.contains("mac") || os.contains("darwin")) {
			if (arm) return MACOS_ARM64;
			if (x64) return MACOS_X64;
		} else if (os.contains("win")) {
			if (x64) return WIN_X64;
		}
		throw new UnsupportedOperationException(
				"No GitLab Language Server binary is available for " + osName + "/" + osArch);
	}
}
