package com.gitlab.eclipse.lsp.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageServerInstallerTest {
	@TempDir
	Path tmp;

	private LanguageServerInstaller installer() {
		return new LanguageServerInstaller(tmp, LanguageServerPlatform.LINUX_X64);
	}

	private static byte[] gzip(byte[] data) throws IOException {
		var out = new ByteArrayOutputStream();
		try (var gz = new GZIPOutputStream(out)) {
			gz.write(data);
		}
		return out.toByteArray();
	}

	@Test
	void buildsDownloadUrl() {
		assertEquals(
			"https://gitlab.com/api/v4/projects/gitlab-org%2Feditor-extensions%2Fgitlab-lsp"
				+ "/packages/generic/gitlab-language-server/9.5.0/gitlab-lsp-9.5.0.tar.gz",
			LanguageServerInstaller.downloadUrl("9.5.0"));
	}

	@Test
	void mapsOnlyNeededEntries() {
		var i = installer();
		assertEquals(i.binaryPath(), i.targetFor("./bin/gitlab-lsp-linux-x64"));
		assertEquals(i.installDir().resolve("vendor/grammars/tree-sitter-java.wasm"),
				i.targetFor("./bin/vendor/grammars/tree-sitter-java.wasm"));
		assertNull(i.targetFor("./bin/gitlab-lsp-win-x64.exe")); // other platform
		assertNull(i.targetFor("./bin/main.js.map"));
		assertNull(i.targetFor("./bin/vendor/../../evil"));      // path traversal
	}

	@Test
	void extractsBinaryAndVendorAndWritesMarker() throws IOException {
		byte[] binary = "#!/bin/false\n".getBytes(StandardCharsets.UTF_8);
		byte[] wasm = new byte[700];
		byte[] tarGz = gzip(TarArchiveReaderTest.archive(
				TarArchiveReaderTest.dirEntry("./bin/"),
				TarArchiveReaderTest.fileEntry("./bin/gitlab-lsp-linux-x64", binary),
				TarArchiveReaderTest.fileEntry("./bin/main.js.map", new byte[10]),
				TarArchiveReaderTest.fileEntry("./bin/vendor/grammars/tree-sitter-java.wasm", wasm)));

		var i = installer();
		assertFalse(i.isInstalled());
		i.extract(new ByteArrayInputStream(tarGz));

		assertTrue(i.isInstalled());
		assertTrue(Files.isRegularFile(i.binaryPath()));
		assertTrue(Files.isExecutable(i.binaryPath()));
		assertTrue(Files.isRegularFile(i.installDir().resolve("vendor/grammars/tree-sitter-java.wasm")));
		assertFalse(Files.exists(i.installDir().resolve("main.js.map")));
	}

	@Test
	void failsWithoutMarkerWhenBinaryMissing() throws IOException {
		byte[] tarGz = gzip(TarArchiveReaderTest.archive(
				TarArchiveReaderTest.fileEntry("./bin/vendor/grammars/x.wasm", new byte[8])));
		var i = installer();
		assertThrows(IOException.class, () -> i.extract(new ByteArrayInputStream(tarGz)));
		assertFalse(i.isInstalled());
	}
}
