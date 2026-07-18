package com.gitlab.eclipse.lsp.install;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and caches the GitLab Language Server standalone binary from the
 * gitlab-lsp generic package registry. Pure JDK: no OSGi/Eclipse dependencies.
 *
 * Cache layout: {cacheDir}/lsp/{VERSION}/{binary, vendor/**, .complete}
 * The .complete marker is written last; its absence means a partial install
 * that will be redone on the next attempt.
 */
public class LanguageServerInstaller {
	public static final String VERSION = "9.5.0";

	private final Path cacheDir;
	private final LanguageServerPlatform platform;

	public LanguageServerInstaller(Path cacheDir, LanguageServerPlatform platform) {
		this.cacheDir = cacheDir;
		this.platform = platform;
	}

	public static String downloadUrl(String version) {
		return "https://gitlab.com/api/v4/projects/gitlab-org%2Feditor-extensions%2Fgitlab-lsp"
				+ "/packages/generic/gitlab-language-server/" + version + "/gitlab-lsp-" + version + ".tar.gz";
	}

	public Path installDir() {
		return cacheDir.resolve("lsp").resolve(VERSION);
	}

	public Path binaryPath() {
		return installDir().resolve(platform.binaryFileName());
	}

	private Path markerPath() {
		return installDir().resolve(".complete");
	}

	public boolean isInstalled() {
		return Files.isRegularFile(markerPath()) && Files.isRegularFile(binaryPath());
	}

	/** Maps a tar entry name to its install location, or null if the entry is not needed. */
	Path targetFor(String entryName) {
		String name = entryName.startsWith("./") ? entryName.substring(2) : entryName;
		if (name.contains("..")) {
			return null;
		}
		if (name.equals("bin/" + platform.binaryFileName())) {
			return binaryPath();
		}
		if (name.startsWith("bin/vendor/")) {
			return installDir().resolve(name.substring("bin/".length()));
		}
		return null;
	}

	/** Extracts this platform's binary and bin/vendor/** from a gitlab-lsp .tar.gz stream. */
	public void extract(InputStream tarGzStream) throws IOException {
		Files.createDirectories(installDir());
		var tar = new TarArchiveReader(new GZIPInputStream(new BufferedInputStream(tarGzStream)));
		boolean binaryFound = false;
		TarArchiveReader.Entry entry;
		while ((entry = tar.nextEntry()) != null) {
			if (!entry.regularFile()) {
				continue;
			}
			Path target = targetFor(entry.name());
			if (target == null) {
				continue;
			}
			Files.createDirectories(target.getParent());
			try (OutputStream out = Files.newOutputStream(target)) {
				tar.copyEntry(out);
			}
			if (target.equals(binaryPath())) {
				target.toFile().setExecutable(true, false);
				binaryFound = true;
			}
		}
		if (!binaryFound) {
			throw new IOException("Archive did not contain bin/" + platform.binaryFileName());
		}
		Files.writeString(markerPath(), VERSION);
	}

	/**
	 * Downloads (~300 MB, one time) and installs the language server if not cached.
	 * {@code progressBytes} receives the cumulative number of downloaded bytes.
	 */
	public Path install(LongConsumer progressBytes) throws IOException, InterruptedException {
		if (isInstalled()) {
			return binaryPath();
		}
		String url = downloadUrl(VERSION);
		try (HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMinutes(2))
					.GET()
					.build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				try (InputStream body = response.body()) {
					// Close the response body before throwing
				}
				throw new IOException("Language server download failed: HTTP " + response.statusCode()
						+ " for " + url);
			}
			try (InputStream body = countingStream(response.body(), progressBytes)) {
				extract(body);
			}
		}
		return binaryPath();
	}

	private static InputStream countingStream(InputStream in, LongConsumer progressBytes) {
		return new FilterInputStream(in) {
			private long total;

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int n = super.read(b, off, len);
				if (n > 0) {
					total += n;
					progressBytes.accept(total);
				}
				return n;
			}

			@Override
			public int read() throws IOException {
				int b = super.read();
				if (b >= 0) {
					total++;
					progressBytes.accept(total);
				}
				return b;
			}
		};
	}
}
