package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

private const val KEY = "1.0.0.100|9.30.0"
private const val OTHER_KEY = "1.0.0.200|9.31.0"

private val isPosix = !System.getProperty("os.name").lowercase().contains("win")

/** Writes a zip shaped like a language server fragment jar (directory entries, a manifest, the binary). */
private fun fragmentArchive(dir: Path, entries: Map<String, String> = defaultEntries()): Path {
  val archive = Files.createTempFile(dir, "fragment", ".jar")
  ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
    entries.forEach { (name, content) ->
      zip.putNextEntry(ZipEntry(name))
      if (!name.endsWith("/")) zip.write(content.toByteArray())
      zip.closeEntry()
    }
  }
  return archive
}

private fun defaultEntries() = linkedMapOf(
  "META-INF/" to "",
  "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
  "bin/" to "",
  "bin/gitlab-lsp" to "binary",
  "bin/webviews/chat/index.html" to "<html/>",
)

private fun Path.names() = listDirectoryEntries().map { it.name }

private fun renameAtomically(source: Path, target: Path) {
  Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
}

/** Fails like a rename on Windows of a directory whose binary is running. */
private val failingRename: (Path, Path) -> Unit = { _, _ -> throw IOException("in use") }

class LanguageServerExtractionTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var workDir: Path
  lateinit var dataRoot: Path
  lateinit var archive: Path
  var archiveOpens = 0

  fun openArchive(path: Path = archive): () -> ZipFile = {
    archiveOpens++
    ZipFile(path.toFile())
  }

  fun extraction(
    timeoutMillis: Long = 5_000,
    renameAside: (Path, Path) -> Unit = ::renameAtomically,
  ) = LanguageServerExtraction(dataRoot, timeoutMillis.milliseconds, 20.milliseconds, renameAside)

  beforeEach {
    workDir = createTempDirectory("ls-extraction")
    dataRoot = Files.createDirectories(workDir.resolve("data"))
    archive = fragmentArchive(workDir)
    archiveOpens = 0
  }

  afterEach {
    workDir.toFile().deleteRecursively()
  }

  describe("installKey") {
    it("combines the bundle version and the language server version") {
      installKey("1.0.0.100", "9.30.0") shouldBe "1.0.0.100|9.30.0"
    }

    it("uses unknown when the fragment declares no language server version") {
      installKey("1.0.0.100", null) shouldBe "1.0.0.100|unknown"
      installKey("1.0.0.100", "  ") shouldBe "1.0.0.100|unknown"
    }

    it("trims surrounding whitespace from both parts") {
      installKey(" 1.0.0.100\n", "9.30.0\r\n") shouldBe "1.0.0.100|9.30.0"
    }
  }

  describe("installDirectoryName") {
    it("is lsp- followed by 12 hex characters of the key's sha256") {
      val expected = MessageDigest.getInstance("SHA-256")
        .digest(KEY.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
      installDirectoryName(KEY) shouldBe "lsp-$expected"
      installDirectoryName(KEY) shouldMatch Regex("^lsp-[0-9a-f]{12}$")
    }

    it("differs between keys") {
      installDirectoryName(KEY) shouldNotBe installDirectoryName(OTHER_KEY)
    }
  }

  describe("install") {
    it("extracts into the keyed directory, writes the marker last and returns the binary") {
      val binary = extraction().install(KEY, openArchive()).shouldNotBeNull()

      val installDir = dataRoot.resolve(installDirectoryName(KEY))
      binary shouldBe installDir.resolve(INSTALLED_BINARY)
      binary.readText() shouldBe "binary"
      installDir.resolve("bin/webviews/chat/index.html").readText() shouldBe "<html/>"
      installDir.resolve(INSTALL_MARKER).readText() shouldBe KEY
      installDir.resolve("META-INF").exists() shouldBe false
      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("takes the fast path on the second call without opening the archive again") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()
      val marker = dataRoot.resolve(installDirectoryName(KEY)).resolve(INSTALL_MARKER)
      val markerTime = Files.getLastModifiedTime(marker)

      val binary = extraction().install(KEY, openArchive())

      binary shouldBe dataRoot.resolve(installDirectoryName(KEY)).resolve(INSTALLED_BINARY)
      archiveOpens shouldBe 1
      Files.getLastModifiedTime(marker) shouldBe markerTime
    }

    it("installs a different key into a new directory and sweeps the old one") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()

      extraction().install(OTHER_KEY, openArchive()).shouldNotBeNull()

      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(OTHER_KEY), "lsp.lock")
    }

    it("re-extracts a keyed directory that has no marker (interrupted install)") {
      val partial = dataRoot.resolve(installDirectoryName(KEY)).resolve("bin")
      Files.createDirectories(partial)
      partial.resolve("gitlab-lsp").writeText("truncated")

      val binary = extraction().install(KEY, openArchive()).shouldNotBeNull()

      binary.readText() shouldBe "binary"
      archiveOpens shouldBe 1
      dataRoot.resolve(installDirectoryName(KEY)).resolve(INSTALL_MARKER).readText() shouldBe KEY
    }

    it("re-extracts a keyed directory whose marker is there but whose binary is gone") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()
      Files.delete(dataRoot.resolve(installDirectoryName(KEY)).resolve(INSTALLED_BINARY))

      extraction().install(KEY, openArchive()).shouldNotBeNull().readText() shouldBe "binary"
      archiveOpens shouldBe 2
    }

    it("sweeps leftover temp directories and the legacy unversioned lsp directory") {
      Files.createDirectories(dataRoot.resolve("lsp.tmp-123/bin"))
      Files.createDirectories(dataRoot.resolve("lsp/bin"))
      dataRoot.resolve("lsp/bin/gitlab-lsp").writeText("9.3.0")
      dataRoot.resolve("unrelated.txt").writeText("keep")

      extraction().install(KEY, openArchive()).shouldNotBeNull()

      dataRoot.names() shouldContainExactlyInAnyOrder
        listOf(installDirectoryName(KEY), "lsp.lock", "unrelated.txt")
    }

    it("sweeps leftovers on the fast path too") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()
      Files.createDirectories(dataRoot.resolve("lsp.tmp-crashed/bin"))

      extraction().install(KEY, openArchive()).shouldNotBeNull()

      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("deletes a symlink inside a swept directory without following it").config(enabled = isPosix) {
      val outside = workDir.resolve("outside").also { Files.createDirectories(it) }
      outside.resolve("precious.txt").writeText("keep")
      Files.createDirectories(dataRoot.resolve("lsp"))
      Files.createSymbolicLink(dataRoot.resolve("lsp/link"), outside)
      Files.createSymbolicLink(dataRoot.resolve("lsp-0123456789ab"), outside)

      extraction().install(KEY, openArchive()).shouldNotBeNull()

      outside.resolve("precious.txt").readText() shouldBe "keep"
      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("rejects an archive entry that escapes the install directory and leaves nothing behind") {
      val evil = fragmentArchive(workDir, defaultEntries() + ("../../escaped.txt" to "pwned"))

      extraction().install(KEY, openArchive(evil)).shouldBeNull()

      workDir.resolve("escaped.txt").exists() shouldBe false
      dataRoot.names() shouldContainExactlyInAnyOrder listOf("lsp.lock")
    }

    it("rejects an absolute archive entry") {
      val absolute = workDir.resolve("absolute.txt").toAbsolutePath()
      val evil = fragmentArchive(workDir, defaultEntries() + (absolute.toString() to "pwned"))

      extraction().install(KEY, openArchive(evil)).shouldBeNull()

      absolute.exists() shouldBe false
      dataRoot.names() shouldContainExactlyInAnyOrder listOf("lsp.lock")
    }

    it("fails without a marker when the archive carries no binary") {
      val empty = fragmentArchive(workDir, linkedMapOf("bin/webviews/chat/index.html" to "<html/>"))

      extraction().install(KEY, openArchive(empty)).shouldBeNull()

      dataRoot.names() shouldContainExactlyInAnyOrder listOf("lsp.lock")
    }

    it("makes the binary executable").config(enabled = isPosix) {
      extraction().install(KEY, openArchive()).shouldNotBeNull().isExecutable() shouldBe true
    }

    it("gives up after the lock timeout while another holder keeps the install lock") {
      FileChannel.open(dataRoot.resolve("lsp.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        .use { channel ->
          channel.lock().use {
            val started = System.nanoTime()

            extraction(timeoutMillis = 300).install(KEY, openArchive()).shouldBeNull()

            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) shouldBeGreaterThanOrEqual 300
          }
        }

      archiveOpens shouldBe 0
      dataRoot.names() shouldContainExactlyInAnyOrder listOf("lsp.lock")
    }

    it("proceeds once the other holder releases the install lock") {
      val channel = FileChannel.open(dataRoot.resolve("lsp.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val held = channel.lock()

      val install = CompletableFuture.supplyAsync { extraction(timeoutMillis = 10_000).install(KEY, openArchive()) }
      delay(200)
      archiveOpens shouldBe 0
      held.release()
      channel.close()

      install.get(10, TimeUnit.SECONDS).shouldNotBeNull().readText() shouldBe "binary"
    }

    it("leaves no temp directory behind after any install") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()

      dataRoot.names().filter { it.startsWith("lsp.tmp-") }.shouldBeEmpty()
    }
  }

  describe("in-use protection") {
    fun staleInstall(name: String = "lsp-aaaaaaaaaaaa"): Path {
      val dir = dataRoot.resolve(name)
      Files.createDirectories(dir.resolve("bin/webviews"))
      dir.resolve(INSTALLED_BINARY).writeText("old")
      dir.resolve("bin/webviews/index.html").writeText("<html/>")
      dir.resolve(INSTALL_MARKER).writeText("0.0.1|9.3.0")
      dir.resolve(IN_USE_FILE).writeText("")
      return dir
    }

    // Within one JVM a second lock on the same file throws instead of returning null, which is how
    // another holder shows up here; a lock held by another process makes tryLock return null.
    fun assertHeldByThisJvm(installDir: Path) {
      val inUseFile = installDir.resolve(IN_USE_FILE)
      FileChannel.open(inUseFile, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
        shouldThrow<OverlappingFileLockException> { channel.tryLock() }
      }
    }

    it("holds a shared lock on the install it returns") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()

      assertHeldByThisJvm(dataRoot.resolve(installDirectoryName(KEY)))
    }

    it("keeps holding it through repeated installs of the same key, without locking twice") {
      extraction().install(KEY, openArchive()).shouldNotBeNull()
      extraction().install(KEY, openArchive()).shouldNotBeNull()
      extraction().install(KEY, openArchive()).shouldNotBeNull()

      archiveOpens shouldBe 1
      assertHeldByThisJvm(dataRoot.resolve(installDirectoryName(KEY)))
    }

    it("leaves an install that another holder is using untouched, and sweeps it once released") {
      val stale = staleInstall()
      val channel = FileChannel.open(stale.resolve(IN_USE_FILE), StandardOpenOption.READ)
      val held = channel.lock(0, Long.MAX_VALUE, true)
      try {
        extraction().install(KEY, openArchive()).shouldNotBeNull()

        stale.resolve(INSTALLED_BINARY).readText() shouldBe "old"
        stale.resolve("bin/webviews/index.html").readText() shouldBe "<html/>"
        stale.resolve(INSTALL_MARKER).exists() shouldBe true
      } finally {
        held.release()
        channel.close()
      }

      extraction().install(KEY, openArchive()).shouldNotBeNull()

      stale.exists() shouldBe false
      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("removes a stale install nobody uses by renaming it aside to a trash directory first") {
      val stale = staleInstall()
      val renames = mutableListOf<Pair<Path, Path>>()

      val recordingRename = { source: Path, target: Path ->
        renames += source to target
        renameAtomically(source, target)
      }
      extraction(renameAside = recordingRename).install(KEY, openArchive()).shouldNotBeNull()

      renames.map { it.first } shouldBe listOf(stale)
      renames.single().second.name shouldStartWith "lsp.trash-"
      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("removes a stale install that has no in-use file") {
      val stale = staleInstall()
      Files.delete(stale.resolve(IN_USE_FILE))

      extraction().install(KEY, openArchive()).shouldNotBeNull()

      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("leaves a stale install whole when it cannot be renamed aside (Windows: binary in use)") {
      val stale = staleInstall()

      extraction(renameAside = failingRename).install(KEY, openArchive()).shouldNotBeNull()

      stale.resolve(INSTALLED_BINARY).readText() shouldBe "old"
      stale.resolve("bin/webviews/index.html").readText() shouldBe "<html/>"
      stale.resolve(INSTALL_MARKER).exists() shouldBe true
    }

    it("removes the legacy lsp directory only by renaming it aside") {
      Files.createDirectories(dataRoot.resolve("lsp/bin/webviews"))
      dataRoot.resolve("lsp/bin/gitlab-lsp").writeText("9.3.0")
      dataRoot.resolve("lsp/bin/webviews/index.html").writeText("<html/>")

      extraction(renameAside = failingRename).install(KEY, openArchive()).shouldNotBeNull()

      dataRoot.resolve("lsp/bin/gitlab-lsp").readText() shouldBe "9.3.0"
      dataRoot.resolve("lsp/bin/webviews/index.html").readText() shouldBe "<html/>"

      val renames = mutableListOf<Pair<Path, Path>>()
      val recordingRename = { source: Path, target: Path ->
        renames += source to target
        renameAtomically(source, target)
      }
      extraction(renameAside = recordingRename).install(KEY, openArchive()).shouldNotBeNull()

      renames.map { it.first } shouldBe listOf(dataRoot.resolve("lsp"))
      renames.single().second.name shouldStartWith "lsp.trash-"
      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }

    it("deletes leftover trash directories") {
      Files.createDirectories(dataRoot.resolve("lsp.trash-crashed/bin"))
      dataRoot.resolve("lsp.trash-crashed/bin/gitlab-lsp").writeText("old")

      extraction().install(KEY, openArchive()).shouldNotBeNull()

      dataRoot.names() shouldContainExactlyInAnyOrder listOf(installDirectoryName(KEY), "lsp.lock")
    }
  }
})
