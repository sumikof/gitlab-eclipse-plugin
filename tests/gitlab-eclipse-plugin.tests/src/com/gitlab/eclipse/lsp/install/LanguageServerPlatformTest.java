package com.gitlab.eclipse.lsp.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LanguageServerPlatformTest {
	@Test
	void detectsLinuxX64() {
		assertEquals(LanguageServerPlatform.LINUX_X64, LanguageServerPlatform.detect("Linux", "amd64"));
		assertEquals(LanguageServerPlatform.LINUX_X64, LanguageServerPlatform.detect("Linux", "x86_64"));
	}

	@Test
	void detectsLinuxArm64() {
		assertEquals(LanguageServerPlatform.LINUX_ARM64, LanguageServerPlatform.detect("Linux", "aarch64"));
	}

	@Test
	void detectsMac() {
		assertEquals(LanguageServerPlatform.MACOS_ARM64, LanguageServerPlatform.detect("Mac OS X", "aarch64"));
		assertEquals(LanguageServerPlatform.MACOS_X64, LanguageServerPlatform.detect("Mac OS X", "x86_64"));
	}

	@Test
	void detectsWindowsX64() {
		assertEquals(LanguageServerPlatform.WIN_X64, LanguageServerPlatform.detect("Windows 11", "amd64"));
	}

	@Test
	void rejectsUnsupportedPlatform() {
		assertThrows(UnsupportedOperationException.class,
				() -> LanguageServerPlatform.detect("Windows 11", "aarch64"));
		assertThrows(UnsupportedOperationException.class,
				() -> LanguageServerPlatform.detect("SunOS", "sparc"));
	}

	@Test
	void binaryFileNames() {
		assertEquals("gitlab-lsp-linux-arm64", LanguageServerPlatform.LINUX_ARM64.binaryFileName());
		assertEquals("gitlab-lsp-win-x64.exe", LanguageServerPlatform.WIN_X64.binaryFileName());
	}
}
