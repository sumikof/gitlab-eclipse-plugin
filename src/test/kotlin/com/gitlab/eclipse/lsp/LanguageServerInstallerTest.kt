package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.system.Arch
import com.gitlab.eclipse.utils.system.OS
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import org.osgi.framework.Version
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Hashtable
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

private const val LINUX_FRAGMENT = "com.gitlab.eclipse.gitlab-language-server.gtk.linux.x86_64"

private fun fragmentJar(dir: Path): File {
  val jar = dir.resolve("fragment.jar")
  ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
    zip.putNextEntry(ZipEntry("bin/gitlab-lsp"))
    zip.write("binary".toByteArray())
    zip.closeEntry()
  }
  return jar.toFile()
}

class LanguageServerInstallerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var workDir: Path
  lateinit var dataArea: Path
  lateinit var fragment: Bundle

  fun installer(os: OS = OS.LINUX, arch: Arch = Arch.X86_64) = LanguageServerInstaller { os to arch }

  /** Hands out [session] and counts the opens. */
  class CountingReporter : InstallProgressReporter {
    val session = RecordingInstallProgressSession()
    var opens = 0

    override fun open(): InstallProgressSession {
      opens++
      return session
    }
  }

  beforeSpec {
    mockkStatic(FileLocator::class)
  }

  beforeEach {
    workDir = createTempDirectory("ls-installer")
    dataArea = Files.createDirectories(workDir.resolve("data"))
    val jar = fragmentJar(workDir)
    fragment = mockk {
      every { symbolicName } returns LINUX_FRAGMENT
      every { version } returns Version("1.2.3.456")
      every { headers } returns Hashtable(mapOf(LSP_VERSION_HEADER to "9.30.0"))
      every { getDataFile("lsp") } returns dataArea.resolve("lsp").toFile()
    }
    every { Platform.getBundle(LINUX_FRAGMENT) } returns fragment
    every { FileLocator.getBundleFileLocation(fragment) } returns Optional.of(jar)
  }

  afterEach {
    workDir.toFile().deleteRecursively()
  }

  describe("production wiring") {
    it("reports install progress to Eclipse jobs when built without arguments") {
      val reporter = LanguageServerInstaller::class.java.getDeclaredField("reporter")
        .apply { isAccessible = true }
        .get(LanguageServerInstaller())

      reporter.shouldBeInstanceOf<JobInstallProgressReporter>()
    }
  }

  describe("install") {
    it("installs into the directory keyed by the fragment's bundle version and language server version") {
      val path = installer().install().shouldNotBeNull()

      val installDir = dataArea.resolve(installDirectoryName("1.2.3.456|9.30.0"))
      path shouldBe installDir.resolve(INSTALLED_BINARY).toAbsolutePath().toString()
      installDir.resolve(INSTALL_MARKER).readText() shouldBe "1.2.3.456|9.30.0"
    }

    it("keys the install as unknown when the fragment declares no language server version") {
      every { fragment.headers } returns Hashtable()

      installer().install().shouldNotBeNull()

      Files.exists(dataArea.resolve(installDirectoryName("1.2.3.456|unknown"))) shouldBe true
    }

    it("fails when the fragment has no data area") {
      every { fragment.getDataFile("lsp") } returns null

      installer().install().shouldBeNull()
    }

    it("fails when the fragment file cannot be located") {
      every { FileLocator.getBundleFileLocation(fragment) } returns Optional.empty()

      installer().install().shouldBeNull()
    }

    it("falls back to the build output when the fragment is not installed (EquoIDE)") {
      every { Platform.getBundle(LINUX_FRAGMENT) } returns null

      installer().install() shouldBe "${System.getProperty("user.dir")}/build/gitlab-lsp/bin/gitlab-lsp-linux-x64"
    }

    it("returns null for linux/aarch64 without looking up any fragment or the EquoIDE fallback") {
      var lookedUp = false
      every { Platform.getBundle(any<String>()) } answers {
        lookedUp = true
        null
      }

      installer(OS.LINUX, Arch.ARM64).install().shouldBeNull()

      lookedUp shouldBe false
    }

    it("returns null for an unknown OS") {
      installer(OS.UNKNOWN, Arch.X86_64).install().shouldBeNull()
    }

    // #124: by default, and when not asked to abandon, the installer still cleans up after earlier installs.
    it("sweeps an earlier install and leftover temp directories by default") {
      val stale = Files.createDirectories(dataArea.resolve(installDirectoryName("1.2.3.455|9.29.0")).resolve("bin"))
      val temp = Files.createDirectories(dataArea.resolve("lsp.tmp-x"))

      installer().install().shouldNotBeNull()

      Files.exists(stale.parent) shouldBe false
      Files.exists(temp) shouldBe false
    }

    it("sweeps an earlier install and leftover temp directories when not asked to abandon") {
      val stale = Files.createDirectories(dataArea.resolve(installDirectoryName("1.2.3.455|9.29.0")).resolve("bin"))
      val temp = Files.createDirectories(dataArea.resolve("lsp.tmp-x"))

      installer().install(shouldAbandon = { false }).shouldNotBeNull()

      Files.exists(stale.parent) shouldBe false
      Files.exists(temp) shouldBe false
    }

    // #124: the abandon check reaches the extraction, so a stopping plugin stops waiting for the lock.
    it("passes the abandon check on, giving up the wait for another instance's install lock") {
      val channel = FileChannel.open(dataArea.resolve("lsp.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val held = channel.lock()
      val started = System.nanoTime()
      try {
        installer().install(shouldAbandon = { true }).shouldBeNull()
      } finally {
        held.release()
        channel.close()
      }

      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) shouldBeLessThan 5_000
      Files.exists(dataArea.resolve(installDirectoryName("1.2.3.456|9.30.0"))) shouldBe false
    }
  }

  // #142 candidate C: one progress session per install, closed however the install ends (design §3.2-§3.3).
  describe("install progress session") {
    fun reportingInstaller(reporter: InstallProgressReporter, os: OS = OS.LINUX, arch: Arch = Arch.X86_64) =
      LanguageServerInstaller(reporter) { os to arch }

    it("opens and closes one session around a successful install, and passes it to the extraction") {
      val reporter = CountingReporter()

      reportingInstaller(reporter).install().shouldNotBeNull()

      reporter.opens shouldBe 1
      reporter.session.closes.get() shouldBe 1
      reporter.session.events shouldContain "begin EXTRACTING"
    }

    it("opens and closes one session on an unsupported platform") {
      val reporter = CountingReporter()

      reportingInstaller(reporter, OS.LINUX, Arch.ARM64).install().shouldBeNull()

      reporter.opens shouldBe 1
      reporter.session.closes.get() shouldBe 1
    }

    it("opens and closes one session on the EquoIDE fallback") {
      every { Platform.getBundle(LINUX_FRAGMENT) } returns null
      val reporter = CountingReporter()

      reportingInstaller(reporter).install().shouldNotBeNull()

      reporter.opens shouldBe 1
      reporter.session.closes.get() shouldBe 1
    }

    it("opens and closes one session when the extraction throws") {
      // A data area whose root is a regular file: the extraction cannot create it and throws.
      val notADirectory = Files.createFile(workDir.resolve("not-a-directory"))
      every { fragment.getDataFile("lsp") } returns notADirectory.resolve("lsp").toFile()
      val reporter = CountingReporter()

      reportingInstaller(reporter).install().shouldBeNull()

      reporter.opens shouldBe 1
      reporter.session.closes.get() shouldBe 1
    }
  }
})
