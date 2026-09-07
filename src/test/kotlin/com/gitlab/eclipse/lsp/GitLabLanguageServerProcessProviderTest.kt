package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import com.google.gson.JsonParser
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.WorkspaceFolder
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
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
  /**
   * Held shut until [releaseInitializeReply], so a test can place other work — another start,
   * an assertion — between the initialize request and the answer to it. Open by default.
   */
  private val initializeGate: CountDownLatch = CountDownLatch(0),
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

  /**
   * Every framed message the provider sent, in arrival order, so a test can assert on what
   * actually went over the wire rather than on the params object the provider built.
   */
  val receivedMessages: List<String> get() = messages
  private val messages = CopyOnWriteArrayList<String>()

  @Suppress("unused")
  private val responder = Thread {
    try {
      while (true) {
        val body = readFramedMessage() ?: break
        messages += body
        // Notifications (initialized, didChangeConfiguration, ...) have no id: skip them.
        val id = REQUEST_ID.find(body)?.groupValues?.get(1)
        if (id != null) {
          initializeGate.await()
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
    } catch (_: InterruptedException) {
      // Declared by the gate's await(). Nothing in the suite interrupts this daemon thread; the
      // catch is here so that ending the responder is the answer if anything ever does.
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

  fun releaseInitializeReply() {
    initializeGate.countDown()
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

  // Stands in for the projects open in the Eclipse workspace. Two of them, because a single
  // folder cannot tell "the whole workspace" apart from "whichever project happened to be first".
  val eclipseProjects = listOf(
    WorkspaceFolder("file:///w/alpha", "alpha"),
    WorkspaceFolder("file:///w/beta", "beta"),
  )

  fun newProvider(
    factory: (ProcessBuilder) -> Process = {
      FakeLanguageServerProcess(nextInitializeReply).also { fake -> spawnedProcesses += fake }
    },
    wrapper: GitLabLanguageServerWrapper = languageServerWrapper,
  ) = GitLabLanguageServerProcessProvider(
    wrapper,
    configurationService,
    openFilesService,
    proxyManager,
    webviewService,
    installer,
    factory,
  )

  beforeSpec {
    // `workspaceFolders` is a top-level val that calls ResourcesPlugin.getWorkspace();
    // it must be mocked or every test that starts the provider throws in a plain-JVM run.
    mockkStatic("com.gitlab.eclipse.lsp.utils.ProjectsWorkspaceFolderKt")
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
    // The wrapper's snapshot is process-wide too, and the revocation tests below read a real one.
    GitLabLanguageServerWrapper().unregisterLanguageServer()
    nextInitializeReply = InitializeReply.SUCCESS
    every { installer.install() } returns "/fake/language-server"
    every { workspaceFolders } returns eclipseProjects
    // Not covered by relaxUnitFun: the identity-aware revocation returns whether it cleared.
    every { languageServerWrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
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

  describe("session revocation") {
    // Design §21 A26 (1): the running server dies.
    it("revokes the current snapshot when the running server exits on its own") {
      val wrapper = GitLabLanguageServerWrapper()
      val provider = newProvider(wrapper = wrapper)
      provider.start(bundle)
      wrapper.currentSnapshot.shouldNotBeNull()

      // A crash or a self-inflicted exit: nothing goes through stop(), so the exit notification
      // is the only place the snapshot can be revoked.
      spawnedProcesses[0].completeExit()

      eventually(2.seconds) { wrapper.currentSnapshot shouldBe null }
    }

    // Design §21 A26 (2): the process survives, only the handshake fails.
    it("revokes the current snapshot when the running server rejects initialization") {
      val wrapper = GitLabLanguageServerWrapper()
      val fake = FakeLanguageServerProcess(InitializeReply.FAILURE, CountDownLatch(1))
      val provider = newProvider(
        factory = { fake.also { spawnedProcesses += it } },
        wrapper = wrapper,
      )

      provider.start(bundle)
      // Held before the rejection so the registration is observable: an un-initialized proxy is
      // published first, and only the initialize callback can take it back.
      wrapper.currentSnapshot.shouldNotBeNull()
      fake.releaseInitializeReply()

      eventually(2.seconds) { wrapper.currentSnapshot shouldBe null }
      // The process itself never died, so this cannot be the exit notification's doing.
      provider.isRunning shouldBe true
      provider.stop()
    }

    // Design §21 A26 (3): a superseded server's late failure must not disturb its successor.
    it("keeps the newer snapshot when a superseded server's initialization fails late") {
      // The rejection is logged first thing in the branch under test, so waiting for that line
      // proves the branch ran without waiting on the revocation this test is about — a wait on
      // the revocation would also fail when the revocation is simply deleted.
      val log = mockk<ILog>(relaxUnitFun = true)
      val rejectionLogged = CountDownLatch(1)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { log.error("Failed to initialize Language Server", any<Throwable>()) } answers {
        rejectionLogged.countDown()
      }
      val wrapper = GitLabLanguageServerWrapper()
      val superseded = FakeLanguageServerProcess(InitializeReply.FAILURE, CountDownLatch(1))
      val pending = mutableListOf(superseded, FakeLanguageServerProcess(InitializeReply.SUCCESS))
      val provider = newProvider(
        factory = { pending.removeFirst().also { spawnedProcesses += it } },
        wrapper = wrapper,
      )

      // The first server's initialize is still unanswered when the second one takes over. This is
      // the one ordering that really races the lock: the initialize callback runs outside it.
      provider.start(bundle)
      provider.start(bundle)
      val newest = wrapper.currentSnapshot.shouldNotBeNull()

      superseded.releaseInitializeReply()
      rejectionLogged.await(5, TimeUnit.SECONDS) shouldBe true

      // The revocation runs right after the line above, so the window has to stay open long
      // enough for an unconditional one to be seen clearing the successor.
      continually(500.milliseconds) { wrapper.currentSnapshot shouldBe newest }
      provider.stop()
    }
  }

  describe("start") {
    it("sends every project in the Eclipse workspace as a workspace folder on initialize") {
      val provider = newProvider()

      provider.start(bundle)

      // Read off the wire rather than off the params object: what this covers is a self-assignment
      // (`workspaceFolders = workspaceFolders`) that left the field null while the call site still
      // named the right thing, so only the serialized request can tell the two apart.
      eventually(2.seconds) {
        val initialize = spawnedProcesses[0].receivedMessages
          .firstOrNull { message -> "\"method\":\"initialize\"" in message }
          .shouldNotBeNull()
        val folders = JsonParser.parseString(initialize).asJsonObject
          .getAsJsonObject("params")
          .getAsJsonArray("workspaceFolders")
          .shouldNotBeNull()
        folders.map { folder -> folder.asJsonObject["uri"].asString } shouldBe
          eclipseProjects.map { it.uri }
        folders.map { folder -> folder.asJsonObject["name"].asString } shouldBe
          eclipseProjects.map { it.name }
      }

      provider.stop()
    }

    it("declares only the workspaceEdit and showDocument capabilities this cycle can honour") {
      val provider = newProvider()

      provider.start(bundle)

      // Read off the wire, not off the params object, for the same reason as the workspace-folder
      // test above: only the serialized request can catch a property that silently never landed.
      eventually(2.seconds) {
        val initialize = spawnedProcesses[0].receivedMessages
          .firstOrNull { message -> "\"method\":\"initialize\"" in message }
          .shouldNotBeNull()
        val capabilities = JsonParser.parseString(initialize).asJsonObject
          .getAsJsonObject("params")
          .getAsJsonObject("capabilities")

        val workspaceEdit = capabilities.getAsJsonObject("workspace").getAsJsonObject("workspaceEdit")
        workspaceEdit["documentChanges"].asBoolean shouldBe true
        workspaceEdit.getAsJsonArray("resourceOperations").size() shouldBe 0
        workspaceEdit["failureHandling"].asString shouldBe "abort"

        val showDocument = capabilities.getAsJsonObject("window").getAsJsonObject("showDocument")
        showDocument["support"].asBoolean shouldBe true
      }

      provider.stop()
    }

    it("fails fast and stays stopped when the process cannot be created") {
      val provider = newProvider(factory = { throw IOException("spawn refused") })

      shouldThrow<IllegalStateException> { provider.start(bundle) }

      provider.isRunning shouldBe false
    }
  }
})
