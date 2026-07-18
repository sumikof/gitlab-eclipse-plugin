# ステップ1: 基盤修復 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** プラグインを「開発者のMac以外でも起動し、壊れたUIが無く、`mvn verify` でCLI検証できる」状態にする。

**Architecture:** LSPバイナリ取得は純JDKのPOJO群(`LanguageServerPlatform` / `TarArchiveReader` / `LanguageServerInstaller`)で実装し、Eclipse依存(Job / IStartup / LSP4Eプロバイダ)は薄いラッパに分離する。リポジトリは標準Tychoレイアウト(`bundles/` + `tests/`)に再配置し、テストフラグメントでユニットテストを実行する。

**Tech Stack:** Java 21, Eclipse PDE/Tycho 4.0.13, Eclipse 2025-06 ターゲットプラットフォーム, LSP4E, JUnit 5

**Spec:** `docs/superpowers/specs/2026-07-17-step1-foundation-repairs-design.md`

## Global Constraints

- LSPバージョン固定: `9.5.0`。DL元URL: `https://gitlab.com/api/v4/projects/gitlab-org%2Feditor-extensions%2Fgitlab-lsp/packages/generic/gitlab-language-server/<version>/gitlab-lsp-<version>.tar.gz`
- tarball内の必要エントリ: `./bin/gitlab-lsp-{linux-x64,linux-arm64,macos-x64,macos-arm64,win-x64.exe}` と `./bin/vendor/**`
- POJO(`com.gitlab.eclipse.lsp.install` の `LanguageServerPlatform` / `TarArchiveReader` / `LanguageServerInstaller`)にOSGi/Eclipse依存を入れない(純JDKのみ)
- 新規設定キー: `gitlab.languageServer.binaryPath`(既定値 `""` = 自動DL)
- フォールバックホスト: `gitlab.com`
- コミットメッセージ末尾に必ず付ける:
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Ku6m3MQ7LooNZXtkT7QNPb
  ```
- ビルド検証コマンドは常にリポジトリルートで `mvn -q verify`(初回はp2リポジトリ取得で数分かかる)

---

### Task 1: Tychoレイアウトへの再配置とビルド基盤

**Files:**
- Move: `src/` `META-INF/` `plugin.xml` `icons/` `build.properties` `README.md`(READMEはルートに残す。それ以外)→ `bundles/gitlab-eclipse-plugin/` 配下
- Create: `pom.xml`(ルート親)
- Create: `bundles/gitlab-eclipse-plugin/pom.xml`
- Modify: `bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF:5`(バージョンを `0.1.0.qualifier` に)
- Create: `.gitignore`

**Interfaces:**
- Produces: `mvn -q verify` によるヘッドレスビルド。後続全タスクの検証手段。
- Produces: Maven座標 `com.gitlab.eclipse:gitlab-eclipse-parent:0.1.0-SNAPSHOT`(親pom)

- [ ] **Step 1: ファイル移動**

```bash
cd /workspace
mkdir -p bundles/gitlab-eclipse-plugin
git mv src META-INF plugin.xml icons build.properties bundles/gitlab-eclipse-plugin/
```

- [ ] **Step 2: MANIFEST.MFのバージョンをTycho互換に変更**

`bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF` の5行目:

```
Bundle-Version: 0.1.0.qualifier
```

(`0.1.0.beta` から変更。Tychoはpomの `0.1.0-SNAPSHOT` と `.qualifier` を対応付ける)

- [ ] **Step 3: ルート親pomを作成**

`/workspace/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.gitlab.eclipse</groupId>
  <artifactId>gitlab-eclipse-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <tycho.version>4.0.13</tycho.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <modules>
    <module>bundles/gitlab-eclipse-plugin</module>
  </modules>

  <repositories>
    <repository>
      <id>eclipse-2025-06</id>
      <layout>p2</layout>
      <url>https://download.eclipse.org/releases/2025-06</url>
    </repository>
  </repositories>

  <build>
    <plugins>
      <plugin>
        <groupId>org.eclipse.tycho</groupId>
        <artifactId>tycho-maven-plugin</artifactId>
        <version>${tycho.version}</version>
        <extensions>true</extensions>
      </plugin>
      <plugin>
        <groupId>org.eclipse.tycho</groupId>
        <artifactId>target-platform-configuration</artifactId>
        <version>${tycho.version}</version>
        <configuration>
          <executionEnvironment>JavaSE-21</executionEnvironment>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

(`tests/...` モジュールはTask 2で追加する)

- [ ] **Step 4: プラグインモジュールのpomを作成**

`bundles/gitlab-eclipse-plugin/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.gitlab.eclipse</groupId>
    <artifactId>gitlab-eclipse-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>gitlab-eclipse-plugin</artifactId>
  <packaging>eclipse-plugin</packaging>
</project>
```

(TychoはartifactIdとBundle-SymbolicName `gitlab-eclipse-plugin` の一致を要求する)

- [ ] **Step 5: .gitignoreを作成**

`/workspace/.gitignore`:

```
/out/
target/
bin/
.polyglot.*
.tycho-consumer-pom.xml
```

- [ ] **Step 6: ビルド検証**

Run: `cd /workspace && mvn -q verify`
Expected: `BUILD SUCCESS`(初回はp2解決で数分かかる。コンパイルエラーが出た場合はターゲットプラットフォームの不足バンドルを確認)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "build: Restructure to standard Tycho layout with headless build"
```

---

### Task 2: テストフラグメントと LanguageServerPlatform(TDD)

**Files:**
- Create: `tests/gitlab-eclipse-plugin.tests/META-INF/MANIFEST.MF`
- Create: `tests/gitlab-eclipse-plugin.tests/build.properties`
- Create: `tests/gitlab-eclipse-plugin.tests/pom.xml`
- Modify: `pom.xml`(modulesに追加)
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/install/LanguageServerPlatformTest.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerPlatform.java`

**Interfaces:**
- Produces: `enum LanguageServerPlatform { LINUX_X64, LINUX_ARM64, MACOS_X64, MACOS_ARM64, WIN_X64 }`
  - `static LanguageServerPlatform detect(String osName, String osArch)`(非対応は `UnsupportedOperationException`)
  - `String binaryFileName()`(例: `gitlab-lsp-linux-arm64`, `gitlab-lsp-win-x64.exe`)

- [ ] **Step 1: テストフラグメントの骨組みを作成**

`tests/gitlab-eclipse-plugin.tests/META-INF/MANIFEST.MF`:

```
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-Name: GitLab Eclipse Plugin Tests
Bundle-SymbolicName: gitlab-eclipse-plugin.tests
Bundle-Version: 0.1.0.qualifier
Fragment-Host: gitlab-eclipse-plugin;bundle-version="0.1.0"
Bundle-RequiredExecutionEnvironment: JavaSE-21
Import-Package: org.junit.jupiter.api;version="[5.0.0,6.0.0)"
Automatic-Module-Name: gitlab.eclipse.plugin.tests
```

`tests/gitlab-eclipse-plugin.tests/build.properties`:

```
source.. = src/
bin.includes = META-INF/,\
               .
```

`tests/gitlab-eclipse-plugin.tests/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.gitlab.eclipse</groupId>
    <artifactId>gitlab-eclipse-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>gitlab-eclipse-plugin.tests</artifactId>
  <packaging>eclipse-test-plugin</packaging>

  <build>
    <plugins>
      <plugin>
        <groupId>org.eclipse.tycho</groupId>
        <artifactId>tycho-surefire-plugin</artifactId>
        <version>${tycho.version}</version>
        <configuration>
          <useUIHarness>false</useUIHarness>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

ルート `pom.xml` の `<modules>` に追加:

```xml
  <modules>
    <module>bundles/gitlab-eclipse-plugin</module>
    <module>tests/gitlab-eclipse-plugin.tests</module>
  </modules>
```

- [ ] **Step 2: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/install/LanguageServerPlatformTest.java`:

```java
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
```

- [ ] **Step 3: テストが失敗する(コンパイルエラーになる)ことを確認**

Run: `mvn -q verify`
Expected: FAIL(`LanguageServerPlatform` が存在せずコンパイルエラー)

- [ ] **Step 4: 実装を書く**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerPlatform.java`:

```java
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
```

- [ ] **Step 5: テストが通ることを確認**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`、surefireレポートに `LanguageServerPlatformTest` 6件PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Add test fragment and language-server platform detection"
```

---

### Task 3: 最小tar(ustar)リーダー(TDD)

**Files:**
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/install/TarArchiveReaderTest.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/TarArchiveReader.java`

**Interfaces:**
- Produces: `final class TarArchiveReader`
  - `TarArchiveReader(InputStream in)`
  - `record Entry(String name, long size, boolean regularFile)`
  - `Entry nextEntry() throws IOException`(終端で `null`。呼ぶと前エントリの未読データは自動スキップ)
  - `void copyEntry(OutputStream out) throws IOException`(現在エントリのデータを書き出す)
- Consumes: なし(純JDK)

- [ ] **Step 1: 失敗するテストを書く(tarバイト列を自前生成するヘルパ込み)**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/install/TarArchiveReaderTest.java`:

```java
package com.gitlab.eclipse.lsp.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TarArchiveReaderTest {

	// --- ustar test-archive builder -------------------------------------

	private static byte[] header(String name, long size, char typeflag) {
		byte[] h = new byte[512];
		byte[] n = name.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(n, 0, h, 0, n.length);
		writeOctal(h, 100, 8, 0644);          // mode
		writeOctal(h, 108, 8, 0);             // uid
		writeOctal(h, 116, 8, 0);             // gid
		writeOctal(h, 124, 12, size);         // size
		writeOctal(h, 136, 12, 0);            // mtime
		h[156] = (byte) typeflag;
		System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
		h[263] = '0';
		h[264] = '0';
		Arrays.fill(h, 148, 156, (byte) ' '); // checksum field = spaces while summing
		int sum = 0;
		for (byte b : h) sum += b & 0xff;
		byte[] cks = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(cks, 0, h, 148, 8);
		return h;
	}

	private static void writeOctal(byte[] h, int offset, int length, long value) {
		byte[] s = String.format("%0" + (length - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(s, 0, h, offset, s.length);
	}

	static byte[] fileEntry(String name, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header(name, content.length, '0'));
		out.writeBytes(content);
		out.writeBytes(new byte[(512 - (content.length % 512)) % 512]);
		return out.toByteArray();
	}

	static byte[] dirEntry(String name) {
		return header(name, 0, '5');
	}

	static byte[] archive(byte[]... entries) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] e : entries) out.writeBytes(e);
		out.writeBytes(new byte[1024]); // end-of-archive marker
		return out.toByteArray();
	}

	// --- tests ----------------------------------------------------------

	@Test
	void readsFileEntriesInOrder() throws IOException {
		byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
		byte[] b = new byte[600]; // spans two data blocks
		Arrays.fill(b, (byte) 7);
		var tar = new TarArchiveReader(new ByteArrayInputStream(
				archive(fileEntry("./bin/a.txt", a), fileEntry("./bin/b.dat", b))));

		var first = tar.nextEntry();
		assertEquals("./bin/a.txt", first.name());
		assertEquals(5, first.size());
		assertTrue(first.regularFile());
		var out = new ByteArrayOutputStream();
		tar.copyEntry(out);
		assertArrayEquals(a, out.toByteArray());

		var second = tar.nextEntry();
		assertEquals("./bin/b.dat", second.name());
		assertEquals(600, second.size());
		out = new ByteArrayOutputStream();
		tar.copyEntry(out);
		assertArrayEquals(b, out.toByteArray());

		assertNull(tar.nextEntry());
	}

	@Test
	void skipsUnreadDataWhenAdvancing() throws IOException {
		byte[] big = new byte[1500];
		var tar = new TarArchiveReader(new ByteArrayInputStream(
				archive(fileEntry("skipme", big), fileEntry("wanted", "x".getBytes(StandardCharsets.UTF_8)))));
		tar.nextEntry(); // do not copy data
		var second = tar.nextEntry();
		assertEquals("wanted", second.name());
		var out = new ByteArrayOutputStream();
		tar.copyEntry(out);
		assertEquals("x", out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void flagsNonRegularEntries() throws IOException {
		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(dirEntry("./bin/"))));
		var entry = tar.nextEntry();
		assertEquals("./bin/", entry.name());
		assertFalse(entry.regularFile());
		assertNull(tar.nextEntry());
	}

	@Test
	void joinsUstarPrefixField() throws IOException {
		byte[] h = header("file.txt", 0, '0');
		// re-checksum after writing the prefix field
		byte[] prefix = "some/long/dir".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(prefix, 0, h, 345, prefix.length);
		Arrays.fill(h, 148, 156, (byte) ' ');
		int sum = 0;
		for (byte b : h) sum += b & 0xff;
		System.arraycopy(String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII), 0, h, 148, 8);

		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(h)));
		assertEquals("some/long/dir/file.txt", tar.nextEntry().name());
	}
}
```

- [ ] **Step 2: 失敗を確認**

Run: `mvn -q verify`
Expected: FAIL(`TarArchiveReader` 不在のコンパイルエラー)

- [ ] **Step 3: 実装を書く**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/TarArchiveReader.java`:

```java
package com.gitlab.eclipse.lsp.install;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal streaming reader for POSIX ustar archives. Supports exactly what the
 * gitlab-lsp release tarball needs: regular-file extraction by name; all other
 * entry types are surfaced so callers can skip them. Pure JDK: no OSGi/Eclipse
 * dependencies.
 */
public final class TarArchiveReader {
	public record Entry(String name, long size, boolean regularFile) {}

	private final InputStream in;
	private long remaining;
	private long padding;

	public TarArchiveReader(InputStream in) {
		this.in = in;
	}

	/** Advances to the next entry, skipping any unread data. Returns null at end of archive. */
	public Entry nextEntry() throws IOException {
		in.skipNBytes(remaining + padding);
		remaining = 0;
		padding = 0;

		byte[] header = new byte[512];
		if (!readBlock(header)) {
			return null;
		}
		if (isZeroBlock(header)) {
			return null;
		}
		String name = nulTerminated(header, 0, 100);
		String prefix = nulTerminated(header, 345, 155);
		if (!prefix.isEmpty()) {
			name = prefix + "/" + name;
		}
		long size = parseOctal(header, 124, 12);
		byte typeflag = header[156];
		remaining = size;
		padding = (512 - (size % 512)) % 512;
		return new Entry(name, size, typeflag == '0' || typeflag == 0);
	}

	/** Writes the current entry's data to {@code out}. */
	public void copyEntry(OutputStream out) throws IOException {
		byte[] buffer = new byte[8192];
		while (remaining > 0) {
			int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (n < 0) {
				throw new EOFException("Truncated tar entry");
			}
			out.write(buffer, 0, n);
			remaining -= n;
		}
	}

	private boolean readBlock(byte[] block) throws IOException {
		int off = 0;
		while (off < block.length) {
			int n = in.read(block, off, block.length - off);
			if (n < 0) {
				if (off == 0) {
					return false;
				}
				throw new EOFException("Truncated tar header");
			}
			off += n;
		}
		return true;
	}

	private static boolean isZeroBlock(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String nulTerminated(byte[] block, int offset, int length) {
		int end = offset;
		while (end < offset + length && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, StandardCharsets.US_ASCII);
	}

	private static long parseOctal(byte[] block, int offset, int length) {
		long value = 0;
		for (int i = offset; i < offset + length; i++) {
			byte b = block[i];
			if (b == 0 || b == ' ') {
				continue;
			}
			value = (value << 3) + (b - '0');
		}
		return value;
	}
}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`、`TarArchiveReaderTest` 4件PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Add minimal streaming ustar reader for LSP tarball extraction"
```

---

### Task 4: LanguageServerInstaller(TDD)

**Files:**
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/install/LanguageServerInstallerTest.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerInstaller.java`

**Interfaces:**
- Consumes: `LanguageServerPlatform`(Task 2)、`TarArchiveReader`(Task 3)、`TarArchiveReaderTest` のstaticヘルパ `fileEntry` / `dirEntry` / `archive`
- Produces: `class LanguageServerInstaller`
  - `static final String VERSION = "9.5.0"`
  - `LanguageServerInstaller(Path cacheDir, LanguageServerPlatform platform)`
  - `static String downloadUrl(String version)`
  - `Path installDir()` / `Path binaryPath()` / `boolean isInstalled()`
  - `void extract(InputStream tarGzStream) throws IOException`
  - `Path install(LongConsumer progressBytes) throws IOException, InterruptedException`(ネットワークDL+extract。ユニットテスト対象外、Task 9で実機検証)

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/install/LanguageServerInstallerTest.java`:

```java
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
```

- [ ] **Step 2: 失敗を確認**

Run: `mvn -q verify`
Expected: FAIL(`LanguageServerInstaller` 不在のコンパイルエラー)

- [ ] **Step 3: 実装を書く**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerInstaller.java`:

```java
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
		try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl(VERSION))).GET().build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new IOException("Language server download failed: HTTP " + response.statusCode()
						+ " for " + downloadUrl(VERSION));
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
```

- [ ] **Step 4: テストが通ることを確認**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`、`LanguageServerInstallerTest` 4件PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Add language-server installer with cached download and extraction"
```

---

### Task 5: Eclipse配線 — 設定・起動時DLジョブ・プロバイダ書き換え

**Files:**
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/PreferenceConstants.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/PreferenceInitializer.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/GitLabPreferencePage.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerInstallJob.java`
- Create: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerStartup.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageServerProvider.java`
- Modify: `bundles/gitlab-eclipse-plugin/plugin.xml`(startup拡張の追加)

**Interfaces:**
- Consumes: `LanguageServerInstaller` / `LanguageServerPlatform`(Task 2/4)
- Produces: `PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH = "gitlab.languageServer.binaryPath"`
- Produces: `static LanguageServerInstaller LanguageServerStartup.defaultInstaller()`(Task 9の検証でも使用)

このタスクはEclipseランタイム配線のためユニットテストなし。`mvn verify` でのコンパイル+既存テスト維持が検証。

- [ ] **Step 1: 設定定数と既定値を追加**

`PreferenceConstants.java` に追加:

```java
	public static final String LANGUAGE_SERVER_BINARY_PATH = "gitlab.languageServer.binaryPath";
```

`PreferenceInitializer.initializeDefaultPreferences()` に追加:

```java
		PREFERENCE_STORE.setDefault(PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH, "");
```

- [ ] **Step 2: 設定ページにフィールドを追加**

`GitLabPreferencePage.createFieldEditors()` の「Language Server」セクションに追加(import `org.eclipse.jface.preference.FileFieldEditor` は `import org.eclipse.jface.preference.*;` で解決済み):

```java
		addField(new FileFieldEditor(PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH,
				"Language Server Binary (blank = auto-download)", true, getFieldEditorParent()));
```

- [ ] **Step 3: インストールジョブを作成**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerInstallJob.java`:

```java
package com.gitlab.eclipse.lsp.install;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/** Background download of the GitLab Language Server with progress reporting. */
public class LanguageServerInstallJob extends Job {
	private final LanguageServerInstaller installer;

	public LanguageServerInstallJob(LanguageServerInstaller installer) {
		super("Downloading GitLab Language Server " + LanguageServerInstaller.VERSION);
		this.installer = installer;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		monitor.beginTask("Downloading GitLab Language Server (~300 MB, one time)", IProgressMonitor.UNKNOWN);
		var lastReportedMb = new long[] { -1 };
		try {
			installer.install(bytes -> {
				long mb = bytes / (1024 * 1024);
				if (mb / 10 > lastReportedMb[0]) {
					lastReportedMb[0] = mb / 10;
					monitor.subTask(mb + " MB downloaded");
				}
			});
			return Status.OK_STATUS;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Status.CANCEL_STATUS;
		} catch (Exception e) {
			return Status.error(
					"Failed to download the GitLab Language Server. The download will be retried on the next start.",
					e);
		} finally {
			monitor.done();
		}
	}
}
```

- [ ] **Step 4: 起動フックを作成**

`bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/install/LanguageServerStartup.java`:

```java
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
```

`plugin.xml` の `</plugin>` 直前に追加:

```xml
	<extension point="org.eclipse.ui.startup">
		<startup class="com.gitlab.eclipse.lsp.install.LanguageServerStartup"/>
	</extension>
```

- [ ] **Step 5: プロバイダのハードコードを置き換え**

`GitLabLanguageServerProvider.java` — コンストラクタと `getInitializationOptions` を書き換え、`start()` を追加。importに以下を追加:

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

import com.gitlab.eclipse.lsp.install.LanguageServerStartup;
```

コンストラクタ(61-69行目)を置き換え:

```java
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
```

`getInitializationOptions` のバージョンハードコード(97行目・102行目の `"0.1.0-erran"`)を置き換え:

```java
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
```

- [ ] **Step 6: ビルド検証**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`、既存テスト全PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Auto-download language server, replace hardcoded developer paths"
```

---

### Task 6: plugin.xmlクリーンアップと死にコード削除

**Files:**
- Modify: `bundles/gitlab-eclipse-plugin/plugin.xml`
- Delete: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/menus/trim/StatusWidget.java`
- Delete: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/handlers/ShowPluginStatus.java`
- Delete: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/handlers/ToggleDuoChatHandler.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/PreferenceConstants.java`(TANUKI定数削除)
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/PreferenceInitializer.java`(TANUKI既定値削除)
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/GitLabPreferencePage.java`(TANUKIフィールド削除)
- Modify: `bundles/gitlab-eclipse-plugin/META-INF/MANIFEST.MF`(`org.eclipse.jface.text` 削除)

**Interfaces:**
- Consumes: なし
- Produces: 未定義参照ゼロのplugin.xml。残る拡張: views / preferencePages / preferences initializer / lsp4e languageServer / ui.startup(Task 5)

- [ ] **Step 1: plugin.xmlから壊れた宣言を削除**

以下を丸ごと削除:

1. `org.eclipse.ui.commands` 拡張(空カテゴリのみのブロック、17-20行目)
2. `org.eclipse.ui.menus` 拡張全体(エディタ右クリックのGitLab Duoサブメニュー4コマンド、トリムツールバー、statusWidgetMenuプルダウンの計3つの `menuContribution`、26-112行目)
3. `org.eclipse.ui.workbench.texteditor.codeMiningProviders` 拡張(存在しない `MyCodeMiningProvider` を参照、185-189行目)
4. コメントアウト済みの `org.eclipse.ui.bindings` TODOコメント(22-23行目)と `org.eclipse.core.runtime.contentTypes` コメントブロック(125-128行目)

残すもの: views(GitLab Duoビュー)、preferencePages、preferences initializer、lsp4e languageServer(contentTypeMapping含む)、ui.startup。

- [ ] **Step 2: 死にコードを削除**

```bash
git rm bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/menus/trim/StatusWidget.java
git rm bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/handlers/ShowPluginStatus.java
git rm bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/handlers/ToggleDuoChatHandler.java
```

- [ ] **Step 3: TANUKI設定を削除**

- `PreferenceConstants.java`: `TANUKI_ONLY_SHOW_CODE_MININGS` 行を削除
- `PreferenceInitializer.java`: `// Dev tooling` コメントと `TANUKI_ONLY_SHOW_CODE_MININGS` のsetDefault行を削除
- `GitLabPreferencePage.java`: `"Tanuki? Why not enable code mining?"` のaddField行を削除

- [ ] **Step 4: MANIFESTから未使用依存を削除**

`org.eclipse.jface.text` はCodeMining用だった(削除後は参照なし)。`META-INF/MANIFEST.MF` のRequire-Bundleから最終行 `org.eclipse.jface.text;bundle-version="3.25.200"` を削除し、前行 `org.eclipse.equinox.security;bundle-version="1.4.400"` の末尾カンマを除去。

- [ ] **Step 5: ビルド検証**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`。`grep -rn "jface.text\|codemining\|Tanuki\|StatusWidget\|ShowPluginStatus\|ToggleDuoChat" bundles/` がヒット0件

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix: Remove broken menu declarations, code mining stub, and dead code"
```

---

### Task 7: SecretStorageのホスト導出(TDD)

**Files:**
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/storage/SecretStorageHostTest.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/storage/SecretStorage.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageServerProvider.java:52`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/preferences/GitLabPreferencePage.java:28`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/views/LanguageServerBrowserView.java:139`

**Interfaces:**
- Produces: `static String SecretStorage.hostOf(String url)`(null/空/不正/ホスト無しは `"gitlab.com"`)
- Produces: `static SecretStorage SecretStorage.forConfiguredInstance()`(設定 `gitlab.url` から導出したホストのストレージ)

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/storage/SecretStorageHostTest.java`:

```java
package com.gitlab.eclipse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecretStorageHostTest {
	@Test
	void derivesHostFromUrl() {
		assertEquals("gitlab.example.com", SecretStorage.hostOf("https://gitlab.example.com"));
		assertEquals("gitlab.example.com", SecretStorage.hostOf("https://gitlab.example.com:8443/group"));
		assertEquals("gitlab.example.com", SecretStorage.hostOf(" https://gitlab.example.com/ "));
	}

	@Test
	void fallsBackToGitlabCom() {
		assertEquals("gitlab.com", SecretStorage.hostOf(null));
		assertEquals("gitlab.com", SecretStorage.hostOf(""));
		assertEquals("gitlab.com", SecretStorage.hostOf("not a url"));
		assertEquals("gitlab.com", SecretStorage.hostOf("/just/a/path"));
	}
}
```

- [ ] **Step 2: 失敗を確認**

Run: `mvn -q verify`
Expected: FAIL(`hostOf` 不在のコンパイルエラー)

- [ ] **Step 3: 実装を書く**

`SecretStorage.java` に追加(import `java.net.URI` / `java.net.URISyntaxException` と、`com.gitlab.eclipse.preferences.PreferenceConstants` / `PreferenceInitializer` を追加):

```java
	/** Secret storage for the host of the configured GitLab instance URL. */
	public static SecretStorage forConfiguredInstance() {
		return new SecretStorage(hostOf(
				PreferenceInitializer.PREFERENCE_STORE.getString(PreferenceConstants.GITLAB_INSTANCE_URL)));
	}

	/** Extracts the host from a GitLab instance URL, falling back to gitlab.com. */
	public static String hostOf(String url) {
		if (url != null && !url.isBlank()) {
			try {
				String host = new URI(url.trim()).getHost();
				if (host != null && !host.isBlank()) {
					return host;
				}
			} catch (URISyntaxException e) {
				// fall through to default
			}
		}
		return "gitlab.com";
	}
```

- [ ] **Step 4: 呼び出し3箇所を置き換え**

`new SecretStorage("gitlab.com")` を `SecretStorage.forConfiguredInstance()` に置換:
- `GitLabLanguageServerProvider.onDidChangeConfiguration()`
- `GitLabPreferencePage.createFieldEditors()`(SecretStringFieldEditorの第1引数)
- `LanguageServerBrowserView.setPersonalAccessTokenAction()`

- [ ] **Step 5: テストが通ることを確認**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`、`SecretStorageHostTest` 2件PASS。`grep -rn 'new SecretStorage("gitlab.com")' bundles/` がヒット0件

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix: Derive secret-storage host from configured instance URL"
```

---

### Task 8: Builderコピペバグ修正(TDD)

**Files:**
- Test: `tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/GitLabLanguageServerConfigurationParamsTest.java`
- Modify: `bundles/gitlab-eclipse-plugin/src/com/gitlab/eclipse/lsp/GitLabLanguageServerConfigurationParams.java:45-48`

**Interfaces:**
- Consumes: `GitLabLanguageServerConfigurationParams`(既存record)
- Produces: `Builder.telemetry(Telemetry)` のみ(誤った `telemetry(FeatureFlags)` オーバーロードは削除)

- [ ] **Step 1: 失敗するテストを書く**

`tests/gitlab-eclipse-plugin.tests/src/com/gitlab/eclipse/lsp/GitLabLanguageServerConfigurationParamsTest.java`:

```java
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
```

- [ ] **Step 2: このテストは現状でも通ることを確認し、真の検証はコンパイルレベルで行う**

Run: `mvn -q verify`
Expected: PASS(Javaは引数型で正しいオーバーロードを選ぶため。バグは「`FeatureFlags` を渡すと `telemetry` という名前で `featureFlags` が書き換わる」という潜在API)。このタスクの本質は誤ったオーバーロードの削除であり、テストは退行防止として残す。

- [ ] **Step 3: 誤ったオーバーロードを削除**

`GitLabLanguageServerConfigurationParams.java` の45-48行目を削除:

```java
		public Builder telemetry(GitLabLanguageServerConfigurationParams.FeatureFlags featureFlags) {
			this.featureFlags = featureFlags;
			return this;
		}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`、`GitLabLanguageServerConfigurationParamsTest` 2件PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix: Remove telemetry builder overload that overwrote featureFlags"
```

---

### Task 9: エンドツーエンド手動検証

**Files:** 変更なし(検証のみ。結果はPR説明に記載する)

**Interfaces:**
- Consumes: `LanguageServerInstaller` / `LanguageServerPlatform`(Task 2/4の成果物)

- [ ] **Step 1: フルビルドを確認**

Run: `cd /workspace && mvn verify 2>&1 | tail -20`
Expected: `BUILD SUCCESS`、全テスト(Platform 6 + Tar 4 + Installer 4 + SecretStorage 2 + Params 2 = 18件)PASS

- [ ] **Step 2: 実機でDL→展開→実行権限を検証(このdevcontainerはlinux-arm64)**

```bash
cat > /tmp/claude-1000/-workspace/9926171f-106a-4ce2-bebf-a0c7bebee586/scratchpad/InstallSmokeTest.java <<'EOF'
import java.nio.file.Path;
import com.gitlab.eclipse.lsp.install.LanguageServerInstaller;
import com.gitlab.eclipse.lsp.install.LanguageServerPlatform;

public class InstallSmokeTest {
	public static void main(String[] args) throws Exception {
		var platform = LanguageServerPlatform.detect(System.getProperty("os.name"), System.getProperty("os.arch"));
		var installer = new LanguageServerInstaller(Path.of(args[0]), platform);
		var binary = installer.install(bytes -> {
			if (bytes % (50 * 1024 * 1024) < 8192) System.out.println((bytes / (1024*1024)) + " MB...");
		});
		System.out.println("installed: " + binary + " installed=" + installer.isInstalled());
	}
}
EOF
java -cp /workspace/bundles/gitlab-eclipse-plugin/target/classes:/tmp/claude-1000/-workspace/9926171f-106a-4ce2-bebf-a0c7bebee586/scratchpad \
  /tmp/claude-1000/-workspace/9926171f-106a-4ce2-bebf-a0c7bebee586/scratchpad/InstallSmokeTest.java \
  /tmp/claude-1000/-workspace/9926171f-106a-4ce2-bebf-a0c7bebee586/scratchpad/lsp-cache
```

Expected: 進捗が表示され、最後に `installed: .../lsp/9.5.0/gitlab-lsp-linux-arm64 installed=true`

- [ ] **Step 3: DLしたバイナリが起動することを確認**

```bash
ls -la /tmp/claude-1000/-workspace/9926171f-106a-4ce2-bebf-a0c7bebee586/scratchpad/lsp-cache/lsp/9.5.0/
/tmp/claude-1000/-workspace/9926171f-106a-4ce2-bebf-a0c7bebee586/scratchpad/lsp-cache/lsp/9.5.0/gitlab-lsp-linux-arm64 --version
```

Expected: バイナリと `vendor/` と `.complete` が存在し、`--version` がバージョン文字列を出力して終了する

- [ ] **Step 4: キャッシュ再利用を確認**

同じ `InstallSmokeTest` をもう一度実行。
Expected: 進捗表示なしで即座に `installed=true`(再DLしない)

- [ ] **Step 5: 検証結果を記録**

上記の出力(要約)をPR説明の「検証」セクション用にメモしておく。
