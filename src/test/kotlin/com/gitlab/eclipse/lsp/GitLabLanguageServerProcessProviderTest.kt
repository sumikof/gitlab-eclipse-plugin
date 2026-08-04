package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

private enum class InitializeReply { SUCCESS, FAILURE, SUCCESS_THEN_EXIT }

/**
 * Stands in for the real language-server process so the lifecycle state machine can be
 * exercised headlessly and deterministically. Speaks just enough of the LSP wire protocol
 * to answer the initialize request (success or failure per [initializeReply]); requests
 * without an id (notifications) are ignored. Unlike a real [Process], [destroy] does NOT
 * complete [onExit]; tests deliver the exit notification explicitly via [completeExit],
 * which is exactly the asynchronous gap the provider's identity guard must survive.
 */
private class FakeLanguageServerProcess(
  private val initializeReply: InitializeReply = InitializeReply.SUCCESS,
) : Process() {
  private val exit = CompletableFuture<Process>()

  private val requestSink = PipedInputStream(PIPE_BUFFER_BYTES)
  private val stdin = PipedOutputStream(requestSink)
  private val stdout = PipedInputStream(PIPE_BUFFER_BYTES)
  private val responseSink = PipedOutputStream(stdout)

  // Connected-but-never-written pipe: the stderr pull loop blocks instead of spinning,
  // and shutdownNow() interrupts it (InterruptedIOException ends the task).
  private val stderr = PipedInputStream(PipedOutputStream())

  var destroyed = false
    private set

  @Suppress("unused")
  private val responder = Thread {
    try {
      while (true) {
        val body = readFramedMessage() ?: break
        // Notifications (initialized, didChangeConfiguration, ...) have no id: skip them.
        val id = REQUEST_ID.find(body)?.groupValues?.get(1)
        if (id != null) {
          if (initializeReply == InitializeReply.SUCCESS_THEN_EXIT) {
            // Deterministic respond-then-die: exit is observably done BEFORE the reply is
            // written, so whichever race arm restart sees first (exit or reply), the
            // process is already provably dead and restart must report failure. Completing
            // from a helper thread keeps the responder free: exit dependents include the
            // provider's lock-taking guard, which would otherwise capture this thread and
            // stall the reply until restart times out.
            Thread { completeExit() }.apply {
              isDaemon = true
              name = "fake-language-server-exit"
              start()
            }
            while (!exit.isDone) {
              Thread.yield()
            }
          }
          val reply = when (initializeReply) {
            InitializeReply.SUCCESS, InitializeReply.SUCCESS_THEN_EXIT ->
              """{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{}}}"""
            InitializeReply.FAILURE ->
              """{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"initialize rejected"}}"""
          }
          val bytes = reply.toByteArray(Charsets.UTF_8)
          responseSink.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
          responseSink.write(bytes)
          responseSink.flush()
        }
      }
    } catch (_: IOException) {
      // Pipes closed by destroy(): the fake server is gone.
    }
  }.apply {
    isDaemon = true
    name = "fake-language-server"
    start()
  }

  private fun readFramedMessage(): String? {
    var contentLength = -1
    while (true) {
      val line = readHeaderLine() ?: return null
      if (line.isEmpty()) break
      CONTENT_LENGTH.find(line)?.let { contentLength = it.groupValues[1].toInt() }
    }
    if (contentLength < 0) return null
    val body = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val n = requestSink.read(body, read, contentLength - read)
      if (n < 0) return null
      read += n
    }
    return String(body, Charsets.UTF_8)
  }

  private fun readHeaderLine(): String? {
    val line = StringBuilder()
    while (true) {
      val b = requestSink.read()
      if (b < 0) return null
      if (b == '\n'.code) break
      if (b != '\r'.code) line.append(b.toChar())
    }
    return line.toString()
  }

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
    runCatching { stdin.close() }
    runCatching { requestSink.close() }
    runCatching { responseSink.close() }
    runCatching { stdout.close() }
  }

  override fun onExit(): CompletableFuture<Process> = exit
  override fun pid(): Long = 4242L

  companion object {
    private const val PIPE_BUFFER_BYTES = 1 shl 16

    // Echoes the id verbatim (quoted or bare) so both string and numeric lsp4j ids round-trip.
    private val REQUEST_ID = Regex("\"id\"\\s*:\\s*(\"?[0-9]+\"?)")
    private val CONTENT_LENGTH = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
  }
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
  var nextInitializeReply = InitializeReply.SUCCESS

  fun newProvider(
    factory: (ProcessBuilder) -> Process = {
      FakeLanguageServerProcess(nextInitializeReply).also { fake -> spawnedProcesses += fake }
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
    // The diagnostics registry is a process-wide object and every stop below moves its epoch, so
    // each test starts from a known one rather than from whatever the previous test left.
    DiagnosticGenerationRegistry.resetForTest()
    nextInitializeReply = InitializeReply.SUCCESS
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

  afterSpec {
    // The diagnostics registry is a process-wide object and every stop above moves its epoch.
    DiagnosticGenerationRegistry.resetForTest()
    stopKoin()
  }

  describe("restart") {
    it("stops the current process and starts a new one") {
      val provider = newProvider()

      provider.start(bundle)
      spawnedProcesses.size shouldBe 1
      provider.isRunning shouldBe true

      // The initialize handshake completes against the fake server and the readiness
      // side effects really run (they never fired when the fake did not answer). The
      // callback must pass its captured (non-null) proxy, not rely on the default.
      eventually(2.seconds) {
        val readinessServers = mutableListOf<GitLabLanguageServer?>()
        verify { configurationService.sendConfiguration(captureNullable(readinessServers)) }
        readinessServers.last().shouldNotBeNull()
      }

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

    it("returns false and settles stopped when the server exits right after answering initialize") {
      val provider = newProvider()
      provider.start(bundle)

      nextInitializeReply = InitializeReply.SUCCESS_THEN_EXIT
      provider.restart(bundle) shouldBe false
      provider.isRunning shouldBe false

      nextInitializeReply = InitializeReply.SUCCESS
      provider.restart(bundle) shouldBe true
      provider.isRunning shouldBe true
      provider.stop()
    }

    it("returns false and settles stopped when the new server rejects initialization") {
      val provider = newProvider()
      provider.start(bundle)

      nextInitializeReply = InitializeReply.FAILURE
      provider.restart(bundle) shouldBe false

      spawnedProcesses.size shouldBe 2
      spawnedProcesses[1].destroyed shouldBe true
      provider.isRunning shouldBe false

      nextInitializeReply = InitializeReply.SUCCESS
      provider.restart(bundle) shouldBe true
      provider.isRunning shouldBe true
      provider.stop()
    }
  }

  describe("connection teardown") {
    it("advances the connection epoch when the server is stopped explicitly") {
      val provider = newProvider()
      provider.start(bundle)
      val live = DiagnosticGenerationRegistry.currentEpoch

      provider.stop()

      DiagnosticGenerationRegistry.currentEpoch shouldBe live + 1
    }

    it("advances the connection epoch when the server exits on its own") {
      val provider = newProvider()
      provider.start(bundle)
      val live = DiagnosticGenerationRegistry.currentEpoch

      // A crash or a self-inflicted exit: nothing goes through stop(), so the exit notification is
      // the only place the teardown can run.
      spawnedProcesses[0].completeExit()

      eventually(2.seconds) { DiagnosticGenerationRegistry.currentEpoch shouldBe live + 1 }
      provider.isRunning shouldBe false
    }

    it("tears the next connection down too, once it has started") {
      val provider = newProvider()
      provider.start(bundle)
      val first = DiagnosticGenerationRegistry.currentEpoch
      provider.stop()

      provider.start(bundle)
      provider.stop()

      // Not once per session: every connection that dies has to strand its own markers and waiters.
      DiagnosticGenerationRegistry.currentEpoch shouldBe first + 2
    }

    it("tears the connection down even when an earlier stop step throws") {
      val provider = newProvider()
      provider.start(bundle)
      val live = DiagnosticGenerationRegistry.currentEpoch
      // A late or degraded stop: the lookups at the top of stopLocked() go through Koin, whose
      // scope may already be closed. The connection is gone either way, so its waiters, deadlines
      // and markers have to go with it rather than be discarded along with the failure.
      every { languageServerWrapper.unregisterLanguageServer() } throws
        IllegalStateException("Koin scope is already closed")

      // Still reported: GitLabEclipseStartup.stop() is what contains it, so that the steps after
      // the language server shutdown keep running.
      shouldThrow<IllegalStateException> { provider.stop() }

      DiagnosticGenerationRegistry.currentEpoch shouldBe live + 1
    }

    it("ignores the exit notification of a process that was already replaced") {
      val provider = newProvider()
      provider.start(bundle)
      val oldProcess = spawnedProcesses[0]
      provider.restart(bundle) shouldBe true
      val afterRestart = DiagnosticGenerationRegistry.currentEpoch

      oldProcess.completeExit()

      // The identity guard holds the teardown as well: the superseded process must not strand the
      // connection that replaced it.
      DiagnosticGenerationRegistry.currentEpoch shouldBe afterRestart
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
