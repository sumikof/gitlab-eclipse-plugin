package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.Platform
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import org.osgi.framework.Version
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory

/**
 * Stands in for the real language-server process so the lifecycle state machine can be
 * exercised headlessly and deterministically. Unlike a real [Process], [destroy] does NOT
 * complete [onExit]; tests deliver the exit notification explicitly via [completeExit],
 * which is exactly the asynchronous gap the provider's identity guard must survive.
 */
private class FakeLanguageServerProcess : Process() {
  private val exit = CompletableFuture<Process>()
  private val stdin = ByteArrayOutputStream()
  private val stdout = ByteArrayInputStream(ByteArray(0))

  // Connected-but-never-written pipe: the stderr pull loop blocks instead of spinning,
  // and shutdownNow() interrupts it (InterruptedIOException ends the task).
  private val stderr = PipedInputStream(PipedOutputStream())

  var destroyed = false
    private set

  fun completeExit() {
    exit.complete(this)
  }

  override fun getOutputStream(): OutputStream = stdin
  override fun getInputStream(): InputStream = stdout
  override fun getErrorStream(): InputStream = stderr
  override fun waitFor(): Int {
    exit.get()
    return 0
  }

  override fun exitValue(): Int = if (exit.isDone) 0 else throw IllegalThreadStateException()
  override fun destroy() {
    destroyed = true
  }

  override fun onExit(): CompletableFuture<Process> = exit
  override fun pid(): Long = 4242L
}

class GitLabLanguageServerProcessProviderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val languageServerWrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
  val configurationService = mockk<GitLabLanguageServerConfigurationService>(relaxUnitFun = true)
  val openFilesService = mockk<GitLabLanguageServerOpenFilesService>(relaxUnitFun = true)
  val proxyManager = mockk<LanguageServerProxyManager>()
  val webviewService = mockk<LanguageServerWebviewService>(relaxUnitFun = true)
  val installer = mockk<LanguageServerInstaller>()
  val bundle = mockk<Bundle>()
  val stateDir = createTempDirectory("ls-state").toFile()

  val spawnedProcesses = mutableListOf<FakeLanguageServerProcess>()

  fun newProvider(
    factory: (ProcessBuilder) -> Process = {
      FakeLanguageServerProcess().also { fake -> spawnedProcesses += fake }
    },
  ) = GitLabLanguageServerProcessProvider(
    languageServerWrapper,
    configurationService,
    openFilesService,
    proxyManager,
    webviewService,
    installer,
    factory,
  )

  beforeSpec {
    startKoin {
      modules(
        module {
          single<DidChangeWatchedFileCapability> { mockk(relaxUnitFun = true) }
          single<PluginMessageService> { mockk(relaxUnitFun = true) }
        }
      )
    }
  }

  beforeEach {
    every { installer.install() } returns "/fake/language-server"
    every { proxyManager.getHttpProxyUrl() } returns null
    every { proxyManager.getHttpsProxyUrl() } returns null
    every { proxyManager.getBypassHosts() } returns null
    every { bundle.version } returns Version("0.0.1")

    // Platform is already static-mocked by LoggingKotestExtension; add the stubs
    // start() needs for the state directory and the platform version.
    val platformBundle = mockk<Bundle> { every { version } returns Version("4.30.0") }
    val pluginBundle = mockk<Bundle>()
    val statePath = mockk<IPath> { every { toFile() } returns stateDir }
    every { Platform.getBundle("org.eclipse.platform") } returns platformBundle
    every { Platform.getBundle("com.gitlab.eclipse.gitlab-eclipse-plugin") } returns pluginBundle
    every { Platform.getStateLocation(pluginBundle) } returns statePath
  }

  afterEach {
    spawnedProcesses.forEach { it.completeExit() }
    spawnedProcesses.clear()
    clearAllMocks()
  }

  afterSpec { stopKoin() }

  describe("restart") {
    it("stops the current process and starts a new one") {
      val provider = newProvider()

      provider.start(bundle)
      spawnedProcesses.size shouldBe 1
      provider.isRunning shouldBe true

      provider.restart(bundle) shouldBe true

      spawnedProcesses.size shouldBe 2
      spawnedProcesses[0].destroyed shouldBe true
      spawnedProcesses[1].destroyed shouldBe false
      provider.isRunning shouldBe true

      provider.stop()
      spawnedProcesses[1].destroyed shouldBe true
      provider.isRunning shouldBe false
    }

    it("ignores a late exit notification from the previous process") {
      val provider = newProvider()

      provider.start(bundle)
      val oldProcess = spawnedProcesses[0]

      provider.restart(bundle) shouldBe true

      // The old process's exit notification arrives only now, after the new process is
      // already tracked. Without the identity guard it would null the shared field and
      // the new process could never be stopped again.
      oldProcess.completeExit()

      provider.isRunning shouldBe true

      provider.stop()
      spawnedProcesses[1].destroyed shouldBe true
    }

    it("settles in the stopped state when the restart fails and can be retried") {
      val provider = newProvider()
      provider.start(bundle)

      every { installer.install() } returns null
      provider.restart(bundle) shouldBe false

      spawnedProcesses[0].destroyed shouldBe true
      provider.isRunning shouldBe false
      verify { languageServerWrapper.unregisterLanguageServer() }

      every { installer.install() } returns "/fake/language-server"
      provider.restart(bundle) shouldBe true
      spawnedProcesses.size shouldBe 2
      provider.isRunning shouldBe true

      provider.stop()
    }
  }

  describe("start") {
    it("fails fast and stays stopped when the process cannot be created") {
      val provider = newProvider(factory = { throw IOException("spawn refused") })

      shouldThrow<IllegalStateException> { provider.start(bundle) }

      provider.isRunning shouldBe false
    }
  }
})
