package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerOpenFilesService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerFailureKind
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleStore
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerPhase
import com.gitlab.eclipse.lsp.lifecycle.StartOrigin
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.lsp.webview.LanguageServerWebviewService
import com.google.gson.JsonParser
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.MockKAnswerScope
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.FailureHandlingKind
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

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
  // Rebuilt for every test (beforeEach). A test that stops its provider right after a start or a
  // restart can leave that server's readiness callback still running: it passed the superseded
  // check before stop() took the lock, and its configuration send can land after afterEach has
  // cleared the mocks. On a shared mock that send is counted by whichever test runs next, so a
  // fresh instance per test is what keeps an exact count about the test's own connections.
  var configurationService = mockk<GitLabLanguageServerConfigurationService>(relaxUnitFun = true)
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
    configurationService = mockk(relaxUnitFun = true)
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
    spawnedProcesses.forEach { fake -> fake.completeExit() }
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
        val readinessHandles = mutableListOf<LanguageServerHandle?>()
        verify { configurationService.sendConfiguration(captureNullable(readinessHandles)) }
        readinessHandles.last().shouldNotBeNull()
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

  describe("connection epoch on the published handle (A13b-12)") {
    it("publishes the client's epoch with the proxy and sends the readiness configuration to that very handle") {
      val registered = mutableListOf<LanguageServerHandle>()
      every { languageServerWrapper.registerLanguageServer(capture(registered)) } returns Unit
      val provider = newProvider()

      provider.start(bundle)
      eventually(2.seconds) { verify { configurationService.sendConfiguration(any<LanguageServerHandle>()) } }
      provider.restart(bundle) shouldBe true
      eventually(2.seconds) {
        verify(exactly = 2) { configurationService.sendConfiguration(any<LanguageServerHandle>()) }
      }

      // Each connection's handle carries the epoch its client was built at: the first one, then
      // the one the restart's stop advanced to. Not a later read of the registry.
      registered.map { it.connectionEpoch } shouldBe listOf(0L, 1L)
      val readinessHandles = mutableListOf<LanguageServerHandle?>()
      verify { configurationService.sendConfiguration(captureNullable(readinessHandles)) }
      // By reference: the configuration send is bound to the handle that was published, not to a
      // copy rebuilt from the proxy, so its epoch cannot come from anywhere else.
      readinessHandles.size shouldBe 2
      readinessHandles[0] shouldBeSameInstanceAs registered[0]
      readinessHandles[1] shouldBeSameInstanceAs registered[1]
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
        factory = { fake.also { spawned -> spawnedProcesses += spawned } },
        wrapper = wrapper,
      )

      provider.start(bundle)
      // Held before the rejection so the registration is observable: an un-initialized proxy is
      // published first, and only the initialize callback can take it back.
      wrapper.currentSnapshot.shouldNotBeNull()
      fake.releaseInitializeReply()

      eventually(2.seconds) { wrapper.currentSnapshot shouldBe null }
      // The process itself never exited, so this cannot be the exit notification's doing. Since LS-02
      // a rejected initialize also stops the (still tracked) process (design §5.3): it settles stopped.
      eventually(2.seconds) { provider.isRunning shouldBe false }
      fake.destroyed shouldBe true
    }

    // Design §21 A26 (3): a superseded server's late failure must not disturb its successor.
    it("keeps the newer snapshot when a superseded server's initialization fails late") {
      // The rejection is logged by the branch under test, so waiting for that line proves the branch
      // ran without waiting on the revocation this test is about — a wait on the revocation would
      // also fail when the revocation is simply deleted.
      val log = mockk<ILog>(relaxUnitFun = true)
      val rejectionLogged = CountDownLatch(1)
      every { Platform.getLog(any<Bundle>()) } returns log
      // LS-02: the superseded server's rejection is logged by exception class only, after the branch
      // has run (design §5.3, §10).
      every {
        log.info(match<String> { it.startsWith("Ignoring initialization failure from a superseded Language Server") })
      } answers { rejectionLogged.countDown() }
      val wrapper = GitLabLanguageServerWrapper()
      val superseded = FakeLanguageServerProcess(InitializeReply.FAILURE, CountDownLatch(1))
      val pending = mutableListOf(superseded, FakeLanguageServerProcess(InitializeReply.SUCCESS))
      val provider = newProvider(
        factory = { pending.removeFirst().also { spawned -> spawnedProcesses += spawned } },
        wrapper = wrapper,
      )

      // The first server's initialize is still unanswered when the second one takes over. This is
      // the one ordering that really races the lock: the initialize callback runs outside it.
      provider.start(bundle)
      provider.start(bundle)
      val newest = wrapper.currentSnapshot.shouldNotBeNull()

      superseded.releaseInitializeReply()
      rejectionLogged.await(5, TimeUnit.SECONDS) shouldBe true

      // The revocation runs just before the line above; the window stays open long enough for an
      // unconditional one to be seen clearing the successor.
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
        workspaceEdit["failureHandling"].asString shouldBe FailureHandlingKind.Abort

        val showDocument = capabilities.getAsJsonObject("window").getAsJsonObject("showDocument")
        showDocument["support"].asBoolean shouldBe true

        // Guards the overload bug this task found: a 3-arg ClientCapabilities(...) call silently
        // boxes the whole window block into `experimental` instead of `window`.
        capabilities.has("experimental") shouldBe false
      }

      provider.stop()
    }

    // start() runs inside bundle activation, which has no catch of its own: a throw there would skip
    // the rest of activation (token refresh, save listener, key bindings).
    it("logs and stays stopped without throwing when the process cannot be created") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val provider = newProvider(factory = { throw IOException("spawn refused") })

      shouldNotThrowAny { provider.start(bundle) }

      provider.isRunning shouldBe false
      // Unlike a missing installation, nothing else has reported this failure: it must stay an error.
      // LS-02 (design §5.3, §10): the exception class only, never its message or stack (paths).
      verify(exactly = 1) { log.error("Failed to start the Language Server (IOException).") }
      verify(exactly = 0) { log.error(any(), any<Throwable>()) }
    }

    it("logs and stays stopped without throwing when the language server cannot be installed") {
      every { installer.install() } returns null
      val provider = newProvider()

      shouldNotThrowAny { provider.start(bundle) }

      provider.isRunning shouldBe false
      spawnedProcesses.size shouldBe 0
    }

    // The installer has already logged why (for example "not bundled for linux/aarch64"): a second
    // error with a stack trace would only bury that one clear line.
    it("adds no error of its own when there is no installation") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { installer.install() } returns null
      val provider = newProvider()

      provider.start(bundle)

      verify(exactly = 0) { log.error(any(), any<Throwable>()) }
      verify(exactly = 0) { log.error(any<String>()) }
      provider.isRunning shouldBe false
    }

    it("can still be restarted after a start that failed to install") {
      every { installer.install() } returns null
      val provider = newProvider()
      provider.start(bundle)

      every { installer.install() } returns "/fake/language-server"
      provider.restart(bundle) shouldBe true
      provider.isRunning shouldBe true

      provider.stop()
    }
  }
})

/**
 * LS-02 (design §5.3): how the provider drives the lifecycle store on every start, stop, restart,
 * initialize and exit path. A class of its own (same setup as the suite above) so that neither
 * spec outgrows detekt's LargeClass limit; in this file because the fake process is private to it.
 */
class GitLabLanguageServerProcessProviderLifecycleTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val languageServerWrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
  val configurationService = mockk<GitLabLanguageServerConfigurationService>(relaxUnitFun = true)
  val openFilesService = mockk<GitLabLanguageServerOpenFilesService>(relaxUnitFun = true)
  val proxyManager = mockk<LanguageServerProxyManager>()
  val webviewService = mockk<LanguageServerWebviewService>(relaxUnitFun = true)
  val installer = mockk<LanguageServerInstaller>()
  val bundle = mockk<Bundle>()
  val stateDir = createTempDirectory("ls-state").toFile()

  val spawnedProcesses = CopyOnWriteArrayList<FakeLanguageServerProcess>()
  var nextInitializeReply = InitializeReply.SUCCESS

  fun newProvider(
    factory: (ProcessBuilder) -> Process = {
      FakeLanguageServerProcess(nextInitializeReply).also { fake -> spawnedProcesses += fake }
    },
    store: LanguageServerLifecycleStore = LanguageServerLifecycleStore(),
    initializeTimeout: JavaDuration = JavaDuration.ofSeconds(30),
  ) = GitLabLanguageServerProcessProvider(
    languageServerWrapper,
    configurationService,
    openFilesService,
    proxyManager,
    webviewService,
    installer,
    factory,
    store,
    initializeTimeout,
  )

  beforeSpec {
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
    DiagnosticGenerationRegistry.resetForTest()
    nextInitializeReply = InitializeReply.SUCCESS
    every { installer.install() } returns "/fake/language-server"
    every { workspaceFolders } returns listOf(WorkspaceFolder("file:///w/alpha", "alpha"))
    every { languageServerWrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
    every { proxyManager.getHttpProxyUrl() } returns null
    every { proxyManager.getHttpsProxyUrl() } returns null
    every { proxyManager.getBypassHosts() } returns null
    every { bundle.version } returns Version("0.0.1")
    val platformBundle = mockk<Bundle> { every { version } returns Version("4.30.0") }
    val pluginBundle = mockk<Bundle>()
    val statePath = mockk<IPath> { every { toFile() } returns stateDir }
    every { Platform.getBundle("org.eclipse.platform") } returns platformBundle
    every { Platform.getBundle("com.gitlab.eclipse.gitlab-eclipse-plugin") } returns pluginBundle
    every { Platform.getStateLocation(pluginBundle) } returns statePath
  }

  afterEach {
    spawnedProcesses.forEach { fake ->
      fake.completeExit()
      fake.releaseInitializeReply()
    }
    spawnedProcesses.clear()
    clearAllMocks()
  }

  afterSpec {
    DiagnosticGenerationRegistry.resetForTest()
    stopKoin()
  }

  // ---- LS-02: lifecycle state store wiring (design §5.3) ----

  /**
   * Routes the provider's ILog into a list. The Throwable overloads are recorded with the
   * throwable's text, so a stack trace or exception message reaching the log shows up as a
   * "THROWABLE:" entry (and its sentinel text) in the list.
   */
  fun captureLog(): MutableList<String> {
    val messages = CopyOnWriteArrayList<String>()
    val log = mockk<ILog>(relaxUnitFun = true)
    every { Platform.getLog(any<Bundle>()) } returns log
    every { log.info(any<String>()) } answers { messages += firstArg<String>() }
    every { log.warn(any<String>()) } answers { messages += firstArg<String>() }
    every { log.error(any<String>()) } answers { messages += firstArg<String>() }
    fun MockKAnswerScope<Unit, Unit>.recordThrowable() {
      messages += "THROWABLE:${firstArg<String>()} ${secondArg<Throwable>()}"
    }
    every { log.info(any(), any<Throwable>()) } answers { recordThrowable() }
    every { log.warn(any(), any<Throwable>()) } answers { recordThrowable() }
    every { log.error(any(), any<Throwable>()) } answers { recordThrowable() }
    return messages
  }

  fun List<String>.shouldBePrivate() {
    filter { "SECRET-PATH" in it } shouldBe emptyList()
    filter { it.startsWith("THROWABLE:") } shouldBe emptyList()
  }

  fun recordTransitions(store: LanguageServerLifecycleStore): List<LanguageServerLifecycleState> =
    CopyOnWriteArrayList<LanguageServerLifecycleState>().also { recorded ->
      store.addListener { _, new -> recorded += new }
    }

  fun LanguageServerLifecycleState.phaseAndGeneration() = phase to generation

  fun List<LanguageServerLifecycleState>.phases() = map { it.phaseAndGeneration() }

  /** A fake whose initialize request is never answered until the test releases it. */
  fun unansweredFake() = FakeLanguageServerProcess(InitializeReply.SUCCESS, CountDownLatch(1))

  describe("lifecycle state on start (LS-02)") {
    it("records INSTALL_FAILED when there is no installation") {
      every { installer.install() } returns null
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(store = store)

      provider.start(bundle)

      store.snapshot() shouldBe LanguageServerLifecycleState(
        LanguageServerPhase.FAILED,
        1,
        StartOrigin.STARTUP,
        LanguageServerFailureKind.INSTALL_FAILED,
      )
      spawnedProcesses.size shouldBe 0
    }

    it("records START_FAILED when the process cannot be spawned and logs no exception text") {
      val log = captureLog()
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(factory = { throw IOException("SECRET-PATH /home/u/ls") }, store = store)

      provider.start(bundle)

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.START_FAILED
      provider.isRunning shouldBe false
      log shouldNotContain "Failed to start the Language Server."
      log.contains("Failed to start the Language Server (IOException).") shouldBe true
      log.shouldBePrivate()
    }

    it("records INITIALIZE_FAILED and stops the process when initialize is rejected") {
      val log = captureLog()
      val store = LanguageServerLifecycleStore()
      nextInitializeReply = InitializeReply.FAILURE
      val provider = newProvider(store = store)

      provider.start(bundle)

      eventually(5.seconds) {
        store.snapshot().phase shouldBe LanguageServerPhase.FAILED
        store.snapshot().failure shouldBe LanguageServerFailureKind.INITIALIZE_FAILED
        spawnedProcesses[0].destroyed shouldBe true
      }
      provider.isRunning shouldBe false
      // The LS's own error text ("initialize rejected") is LS message text: never logged.
      log.filter { "initialize rejected" in it } shouldBe emptyList()
      log.shouldBePrivate()
    }

    it("records INITIALIZE_TIMEOUT and stops the process when initialize is never answered") {
      val store = LanguageServerLifecycleStore()
      val fake = unansweredFake()
      val provider = newProvider(
        factory = { fake.also { spawned -> spawnedProcesses += spawned } },
        store = store,
        initializeTimeout = JavaDuration.ofMillis(200),
      )

      provider.start(bundle)
      store.snapshot().phase shouldBe LanguageServerPhase.STARTING

      eventually(5.seconds) {
        store.snapshot().failure shouldBe LanguageServerFailureKind.INITIALIZE_TIMEOUT
        fake.destroyed shouldBe true
      }
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      provider.isRunning shouldBe false
      fake.releaseInitializeReply()
    }

    it("records EXITED_DURING_STARTUP when the process exits before initialize completes") {
      val store = LanguageServerLifecycleStore()
      val fake = unansweredFake()
      val provider = newProvider(factory = { fake.also { spawned -> spawnedProcesses += spawned } }, store = store)

      provider.start(bundle)
      fake.completeExit()

      eventually(5.seconds) {
        store.snapshot().failure shouldBe LanguageServerFailureKind.EXITED_DURING_STARTUP
      }
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      provider.isRunning shouldBe false
      fake.releaseInitializeReply()
    }

    it("records CRASHED when the running process exits and logs only the exit code") {
      val log = captureLog()
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(store = store)

      provider.start(bundle)
      eventually(5.seconds) { store.snapshot().phase shouldBe LanguageServerPhase.RUNNING }
      log.contains("Initialized Language Server.") shouldBe true

      spawnedProcesses[0].completeExit()

      eventually(5.seconds) { store.snapshot().failure shouldBe LanguageServerFailureKind.CRASHED }
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().generation shouldBe 1L
      log.contains("Language Server exited unexpectedly (exit code 0).") shouldBe true
      log.shouldBePrivate()
    }

    it("records STOPPED on an explicit stop and never FAILED, even after the exit arrives") {
      val store = LanguageServerLifecycleStore()
      val recorded = recordTransitions(store)
      val provider = newProvider(store = store)

      provider.start(bundle)
      eventually(5.seconds) { store.snapshot().phase shouldBe LanguageServerPhase.RUNNING }
      provider.stop()
      spawnedProcesses[0].completeExit()

      store.snapshot().phase shouldBe LanguageServerPhase.STOPPED
      recorded.map { it.phase } shouldNotContain LanguageServerPhase.FAILED
    }

    it("spawns nothing once the store is closed (bundle stopping)") {
      val store = LanguageServerLifecycleStore()
      store.close()
      val provider = newProvider(store = store)

      shouldNotThrowAny { provider.start(bundle) }
      provider.restart(bundle) shouldBe false

      spawnedProcesses.size shouldBe 0
      provider.isRunning shouldBe false
    }
  }

  describe("lifecycle state on restart (LS-02)") {
    it("publishes STOPPED(g1), then STARTING(g2), then RUNNING(g2)") {
      val store = LanguageServerLifecycleStore()
      val recorded = recordTransitions(store)
      val provider = newProvider(store = store)
      provider.start(bundle)
      eventually(5.seconds) { store.snapshot().phase shouldBe LanguageServerPhase.RUNNING }

      provider.restart(bundle) shouldBe true

      eventually(5.seconds) { store.snapshot().phaseAndGeneration() shouldBe (LanguageServerPhase.RUNNING to 2L) }
      recorded.phases() shouldBe listOf(
        LanguageServerPhase.STARTING to 1L,
        LanguageServerPhase.RUNNING to 1L,
        LanguageServerPhase.STOPPED to 1L,
        LanguageServerPhase.STARTING to 2L,
        LanguageServerPhase.RUNNING to 2L,
      )
      store.snapshot().origin shouldBe StartOrigin.RESTART
      provider.stop()
    }

    it("returns false with EXITED_DURING_STARTUP when the new server exits before initialize") {
      val store = LanguageServerLifecycleStore()
      val fake = unansweredFake()
      val provider = newProvider(
        factory = {
          fake.also { spawned ->
            spawnedProcesses += spawned
            Thread {
              Thread.sleep(100)
              spawned.completeExit()
            }.apply { isDaemon = true }.start()
          }
        },
        store = store,
        initializeTimeout = JavaDuration.ofSeconds(10),
      )

      provider.restart(bundle) shouldBe false

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.EXITED_DURING_STARTUP
      fake.destroyed shouldBe true
      fake.releaseInitializeReply()
    }

    it("returns false with EXITED_DURING_STARTUP when the new server exits right after answering") {
      val store = LanguageServerLifecycleStore()
      nextInitializeReply = InitializeReply.SUCCESS_THEN_EXIT
      val provider = newProvider(store = store)

      provider.restart(bundle) shouldBe false

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.EXITED_DURING_STARTUP
    }

    it("returns false with INITIALIZE_TIMEOUT when the new server never answers") {
      val store = LanguageServerLifecycleStore()
      val fake = unansweredFake()
      val provider = newProvider(
        factory = { fake.also { spawned -> spawnedProcesses += spawned } },
        store = store,
        initializeTimeout = JavaDuration.ofMillis(200),
      )

      provider.restart(bundle) shouldBe false

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.INITIALIZE_TIMEOUT
      fake.destroyed shouldBe true
      provider.isRunning shouldBe false
      fake.releaseInitializeReply()
    }

    it("returns false with INITIALIZE_FAILED when the new server rejects initialize") {
      val log = captureLog()
      val store = LanguageServerLifecycleStore()
      nextInitializeReply = InitializeReply.FAILURE
      val provider = newProvider(store = store)

      provider.restart(bundle) shouldBe false

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.INITIALIZE_FAILED
      log.filter { "initialize rejected" in it } shouldBe emptyList()
      log.shouldBePrivate()
    }

    it("returns false with START_FAILED when the spawn fails, logging no exception text") {
      val log = captureLog()
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(factory = { throw IOException("SECRET-PATH /home/u/ls") }, store = store)

      provider.restart(bundle) shouldBe false

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.START_FAILED
      log.shouldBePrivate()
    }

    it("returns false with INSTALL_FAILED when there is no installation") {
      every { installer.install() } returns null
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(store = store)

      provider.restart(bundle) shouldBe false

      store.snapshot().failure shouldBe LanguageServerFailureKind.INSTALL_FAILED
    }

    it("returns false with START_FAILED when the waiting thread is interrupted") {
      val store = LanguageServerLifecycleStore()
      val fake = unansweredFake()
      val spawned = CountDownLatch(1)
      val provider = newProvider(
        factory = {
          spawnedProcesses += fake
          spawned.countDown()
          fake
        },
        store = store,
        initializeTimeout = JavaDuration.ofSeconds(10),
      )
      val result = AtomicReference<Boolean?>(null)
      val restarting = Thread { result.set(provider.restart(bundle)) }.apply {
        isDaemon = true
        start()
      }

      spawned.await(5, TimeUnit.SECONDS) shouldBe true
      restarting.interrupt()
      restarting.join(5_000)

      result.get() shouldBe false
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.START_FAILED
      fake.destroyed shouldBe true
      fake.releaseInitializeReply()
    }

    it("does not stop the new server when the superseded server's initialize fails late (F1)") {
      val log = captureLog()
      val store = LanguageServerLifecycleStore()
      val superseded = unansweredFake()
      val pending = mutableListOf(superseded, FakeLanguageServerProcess(InitializeReply.SUCCESS))
      val provider = newProvider(
        factory = { pending.removeFirst().also { spawned -> spawnedProcesses += spawned } },
        store = store,
        initializeTimeout = JavaDuration.ofMillis(800),
      )

      provider.start(bundle)
      provider.restart(bundle) shouldBe true
      val successor = spawnedProcesses[1]

      // The first server's initialize future times out only now, after the restart took over.
      eventually(5.seconds) {
        log.any { it.startsWith("Ignoring initialization failure from a superseded Language Server") } shouldBe true
      }

      continually(300.milliseconds) {
        successor.destroyed shouldBe false
        provider.isRunning shouldBe true
        store.snapshot().phaseAndGeneration() shouldBe (LanguageServerPhase.RUNNING to 2L)
      }
      provider.stop()
      superseded.releaseInitializeReply()
    }

    it("ignores a late exit of the superseded server") {
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(store = store)
      provider.start(bundle)
      provider.restart(bundle) shouldBe true
      eventually(5.seconds) { store.snapshot().phaseAndGeneration() shouldBe (LanguageServerPhase.RUNNING to 2L) }
      val recorded = recordTransitions(store)

      spawnedProcesses[0].completeExit()

      recorded shouldBe emptyList()
      store.snapshot().phaseAndGeneration() shouldBe (LanguageServerPhase.RUNNING to 2L)
      provider.isRunning shouldBe true
      provider.stop()
    }

    it("keeps exactly one live process after two sequential restarts") {
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(store = store)
      provider.start(bundle)

      provider.restart(bundle) shouldBe true
      provider.restart(bundle) shouldBe true

      spawnedProcesses.size shouldBe 3
      spawnedProcesses.map { it.destroyed } shouldBe listOf(true, true, false)
      eventually(5.seconds) { store.snapshot().phaseAndGeneration() shouldBe (LanguageServerPhase.RUNNING to 3L) }
      provider.stop()
    }

    it("clears FAILED with STOPPED when stopped after a failure") {
      val store = LanguageServerLifecycleStore()
      every { installer.install() } returns null
      val provider = newProvider(store = store)
      provider.start(bundle)
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED

      provider.stop()

      store.snapshot().phase shouldBe LanguageServerPhase.STOPPED
      store.snapshot().failure shouldBe null
    }
  }
})
