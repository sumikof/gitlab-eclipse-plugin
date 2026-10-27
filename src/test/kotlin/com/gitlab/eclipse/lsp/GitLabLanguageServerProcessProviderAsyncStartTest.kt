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
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

/** Runs submitted tasks only when the test says so, on the test thread. */
private class ManualExecutor : Executor {
  private val queue = ArrayDeque<Runnable>()

  override fun execute(command: Runnable) {
    queue += command
  }

  fun runNext() = queue.removeFirst().run()

  val pending: Int get() = queue.size
}

/** Holds install() until released, and records the thread it ran on and whether it was interrupted. */
private class GatedInstall(private val result: String? = "/fake/language-server") {
  val entered = CountDownLatch(1)
  val release = CountDownLatch(1)
  val threads = CopyOnWriteArrayList<Thread>()
  val maxConcurrent = AtomicInteger()
  private val concurrent = AtomicInteger()

  @Volatile
  var interruptedAtEnd: Boolean? = null

  @Volatile
  var abandonCheck: (() -> Boolean)? = null

  fun answer(abandon: () -> Boolean): String? {
    threads += Thread.currentThread()
    abandonCheck = abandon
    maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
    try {
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
      return result
    } finally {
      concurrent.decrementAndGet()
      interruptedAtEnd = Thread.currentThread().isInterrupted
    }
  }
}

/** Wraps a real executor, recording whatever a task lets escape; [done] opens once a task has finished. */
private class EscapeRecorder(private val real: Executor) : Executor {
  val escaped = CopyOnWriteArrayList<Throwable>()
  val done = CountDownLatch(1)

  override fun execute(command: Runnable) {
    real.execute {
      try {
        command.run()
      } catch (t: Throwable) {
        escaped += t
      } finally {
        done.countDown()
      }
    }
  }
}

/**
 * #124: the provider's start runs the install and the spawn on its own daemon thread, begun under
 * the lifecycle lock so that a stop, a restart or a closed store that comes in between wins. Same
 * setup as the lifecycle suite; a class of its own so that no spec outgrows detekt's LargeClass limit.
 */
class GitLabLanguageServerProcessProviderAsyncStartTest : DescribeSpec({
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
  val executors = CopyOnWriteArrayList<ExecutorService>()

  /** The production executor, shut down (never with shutdownNow) after each test. */
  fun startExecutor(): ExecutorService = newLanguageServerStartExecutor().also { executors += it }

  fun newProvider(
    startExecutor: Executor,
    factory: (ProcessBuilder) -> Process = {
      FakeLanguageServerProcess(InitializeReply.SUCCESS).also { fake -> spawnedProcesses += fake }
    },
    store: LanguageServerLifecycleStore = LanguageServerLifecycleStore(),
    wrapper: GitLabLanguageServerWrapper = languageServerWrapper,
  ) = GitLabLanguageServerProcessProvider(
    wrapper,
    configurationService,
    openFilesService,
    proxyManager,
    webviewService,
    installer,
    factory,
    store,
    startExecutor = startExecutor,
  )

  fun recordTransitions(store: LanguageServerLifecycleStore): List<LanguageServerLifecycleState> =
    CopyOnWriteArrayList<LanguageServerLifecycleState>().also { recorded ->
      store.addListener { _, new -> recorded += new }
    }

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
    every { installer.install(any()) } returns "/fake/language-server"
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
    executors.forEach { executor ->
      executor.shutdown()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
    executors.clear()
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

  describe("asynchronous start (#124)") {
    it("returns while the install is still running, and installs and spawns on the gitlab-ls-start daemon thread") {
      val gate = GatedInstall()
      every { installer.install(any()) } answers { gate.answer(firstArg()) }
      val spawnThreads = CopyOnWriteArrayList<Thread>()
      val provider = newProvider(
        startExecutor = startExecutor(),
        factory = {
          spawnThreads += Thread.currentThread()
          FakeLanguageServerProcess(InitializeReply.SUCCESS).also { fake -> spawnedProcesses += fake }
        },
      )
      val caller = Thread({ provider.start(bundle) }, "fake-activator")

      caller.start()
      gate.entered.await(5, TimeUnit.SECONDS) shouldBe true
      caller.join(5_000)
      // start() returned although install() is still blocked.
      caller.isAlive shouldBe false
      gate.release.countDown()
      eventually(5.seconds) { spawnedProcesses shouldHaveSize 1 }

      gate.threads.single().name shouldBe LANGUAGE_SERVER_START_THREAD_NAME
      gate.threads.single().isDaemon shouldBe true
      spawnThreads.single() shouldBe gate.threads.single()
      provider.stop()
    }

    it("publishes STARTING before start() returns") {
      val store = LanguageServerLifecycleStore()
      val executor = ManualExecutor()
      val provider = newProvider(startExecutor = executor, store = store)

      provider.start(bundle)

      store.snapshot().phase shouldBe LanguageServerPhase.STARTING
      store.snapshot().origin shouldBe StartOrigin.STARTUP
      executor.pending shouldBe 1
      spawnedProcesses shouldHaveSize 0
    }

    it("a queued start does nothing after stop() (S1)") {
      val store = LanguageServerLifecycleStore()
      val executor = ManualExecutor()
      val provider = newProvider(startExecutor = executor, store = store)
      provider.start(bundle)

      provider.stop()
      executor.runNext()

      spawnedProcesses shouldHaveSize 0
      verify(exactly = 0) { installer.install(any()) }
      store.snapshot().phase shouldBe LanguageServerPhase.STOPPED
    }

    it("a queued start does nothing once the store is closed (bundle stop, S1-closed)") {
      val store = LanguageServerLifecycleStore()
      val executor = ManualExecutor()
      val provider = newProvider(startExecutor = executor, store = store)
      provider.start(bundle)

      store.close()
      provider.stop()
      executor.runNext()

      spawnedProcesses shouldHaveSize 0
      verify(exactly = 0) { installer.install(any()) }
    }

    it("a queued start does nothing after a restart, so only the restart's server lives (S1')") {
      val executor = ManualExecutor()
      val provider = newProvider(startExecutor = executor)
      provider.start(bundle)

      provider.restart(bundle) shouldBe true
      executor.runNext()

      spawnedProcesses shouldHaveSize 1
      spawnedProcesses.single().destroyed shouldBe false
      provider.stop()
    }

    it("a second start while the first is pending is a no-op (S5, L-D7)") {
      val executor = ManualExecutor()
      val provider = newProvider(startExecutor = executor)

      provider.start(bundle)
      provider.start(bundle)

      executor.pending shouldBe 1
      executor.runNext()
      spawnedProcesses shouldHaveSize 1
      provider.stop()
    }

    it("does not spawn when the bundle stops while the install runs, and the abandon check sees the close (S2)") {
      val gate = GatedInstall()
      every { installer.install(any()) } answers { gate.answer(firstArg()) }
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(startExecutor = startExecutor(), store = store)
      val transitions = recordTransitions(store)
      provider.start(bundle)
      gate.entered.await(5, TimeUnit.SECONDS) shouldBe true

      store.close()
      gate.abandonCheck.shouldNotBeNull().invoke() shouldBe true
      val stopper = Thread { provider.stop() }.apply { start() }
      gate.release.countDown()
      stopper.join(5_000)

      stopper.isAlive shouldBe false
      spawnedProcesses shouldHaveSize 0
      transitions.none { it.phase == LanguageServerPhase.FAILED } shouldBe true
      gate.interruptedAtEnd shouldBe false
    }

    it("a restart during the start's install waits, then replaces it: one live server, no deadlock (S6)") {
      val gate = GatedInstall()
      val installs = AtomicInteger()
      every { installer.install(any()) } answers {
        if (installs.getAndIncrement() == 0) gate.answer(firstArg()) else "/fake/language-server"
      }
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(startExecutor = startExecutor(), store = store)
      provider.start(bundle)
      gate.entered.await(5, TimeUnit.SECONDS) shouldBe true

      var restarted: Boolean? = null
      val restarter = Thread { restarted = provider.restart(bundle) }.apply { start() }
      eventually(5.seconds) { restarter.state shouldBeIn listOf(Thread.State.BLOCKED, Thread.State.WAITING) }
      gate.release.countDown()
      restarter.join(10_000)

      restarter.isAlive shouldBe false
      restarted shouldBe true
      spawnedProcesses.count { !it.destroyed } shouldBe 1
      store.snapshot().phase shouldBe LanguageServerPhase.RUNNING
      gate.maxConcurrent.get() shouldBe 1
      provider.stop()
    }

    it("never runs two installs at once across a start and several restarts (AC-9)") {
      val concurrent = AtomicInteger()
      val max = AtomicInteger()
      // Never released: each install just holds for a moment, so that overlapping installs would show.
      val hold = CountDownLatch(1)
      every { installer.install(any()) } answers {
        max.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
        hold.await(20, TimeUnit.MILLISECONDS)
        concurrent.decrementAndGet()
        "/fake/language-server"
      }
      val provider = newProvider(startExecutor = startExecutor())

      provider.start(bundle)
      val restarters = (1..3).map { Thread { provider.restart(bundle) }.apply { start() } }
      restarters.forEach { it.join(15_000) }

      restarters.none { it.isAlive } shouldBe true
      max.get() shouldBe 1
      spawnedProcesses.count { !it.destroyed } shouldBe 1
      provider.stop()
    }

    it("never throws and leaves no STARTING behind when the executor rejects the task (AC-4)") {
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(startExecutor = { throw RejectedExecutionException("closed") }, store = store)

      shouldNotThrowAny { provider.start(bundle) }

      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      store.snapshot().failure shouldBe LanguageServerFailureKind.START_FAILED
    }

    it("stops its own process and fails the attempt when the start throws after the spawn (AC-13, R-1)") {
      val store = LanguageServerLifecycleStore()
      // The wrapper registration runs after `process` is published: make it throw.
      val wrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
      every { wrapper.registerLanguageServer(any()) } throws IllegalStateException("boom")
      every { wrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
      val provider = newProvider(startExecutor = { it.run() }, store = store, wrapper = wrapper)

      shouldNotThrowAny { provider.start(bundle) }

      spawnedProcesses.single().destroyed shouldBe true
      provider.isRunning shouldBe false
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
    }

    it("stops its own process and leaves no STARTING behind when an Error is thrown after the spawn (D-2)") {
      val store = LanguageServerLifecycleStore()
      val wrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
      every { wrapper.registerLanguageServer(any()) } throws OutOfMemoryError("test")
      every { wrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
      val provider = newProvider(startExecutor = { runCatching { it.run() } }, store = store, wrapper = wrapper)

      provider.start(bundle)

      spawnedProcesses.single().destroyed shouldBe true
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
    }

    it("cleans up a failed start under the lifecycle lock, so a waiting restart is not undone (D-1, T9)") {
      val wrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
      every { wrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
      var restarter: Thread? = null
      var provider: GitLabLanguageServerProcessProvider? = null
      val registrations = AtomicInteger()
      val secondSpawn = CountDownLatch(1)
      // The failed start's error line comes between its failure and its clean-up, and is written
      // whether or not the store still takes the failure. Under the lifecycle lock, as it must be, the
      // restart cannot spawn during this wait; were the clean-up outside the lock, the restart would
      // spawn here and the clean-up after this wait would stop the restart's server.
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { log.error(match<String> { it.startsWith("Failed to start the Language Server (") }) } answers {
        secondSpawn.await(1, TimeUnit.SECONDS)
      }
      every { wrapper.registerLanguageServer(any()) } answers {
        if (registrations.getAndIncrement() == 0) {
          // The restart arrives while this start still holds the lock, then this start fails.
          val waiting = Thread { provider.shouldNotBeNull().restart(bundle) }.apply { start() }
          restarter = waiting
          // A plain wait: this answer is not a coroutine, so `eventually` is not available here.
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
          while (waiting.state != Thread.State.BLOCKED && System.nanoTime() < deadline) waiting.join(10)
          waiting.state shouldBe Thread.State.BLOCKED
          error("boom")
        }
      }
      provider = newProvider(
        startExecutor = startExecutor(),
        factory = {
          FakeLanguageServerProcess(InitializeReply.SUCCESS).also { fake ->
            spawnedProcesses += fake
            if (spawnedProcesses.size >= 2) secondSpawn.countDown()
          }
        },
        wrapper = wrapper,
      )

      provider.start(bundle)
      eventually(10.seconds) { restarter.shouldNotBeNull().isAlive shouldBe false }

      spawnedProcesses shouldHaveSize 2
      spawnedProcesses[0].destroyed shouldBe true
      spawnedProcesses[1].destroyed shouldBe false
      provider.stop()
    }

    it("does not carry an interrupt into the next start on the same thread (S11, T8)") {
      var first = true
      every { installer.install(any()) } answers {
        if (first) {
          first = false
          Thread.currentThread().interrupt()
          null
        } else {
          "/fake/language-server"
        }
      }
      val executor = startExecutor()
      val provider = newProvider(startExecutor = executor)

      // Fails with INSTALL_FAILED, leaving the interrupt flag set on the start thread.
      provider.start(bundle)
      eventually(5.seconds) { provider.isRunning shouldBe false }
      // A quiescent point: the first task has finished.
      executor.submit<Unit> { }.get(5, TimeUnit.SECONDS)
      provider.start(bundle)

      eventually(5.seconds) { spawnedProcesses shouldHaveSize 1 }
      executor.submit<Boolean> { Thread.currentThread().isInterrupted }.get(5, TimeUnit.SECONDS) shouldBe false
      provider.stop()
    }
  }

  describe("asynchronous start, pinned against regressions (#124)") {
    it("runs the install and the spawn on the gitlab-ls-start daemon thread by default (M1)") {
      val gate = GatedInstall()
      every { installer.install(any()) } answers { gate.answer(firstArg()) }
      val spawnThreads = CopyOnWriteArrayList<Thread>()
      // The constructor's own executor, as Koin builds the provider in production.
      val provider = GitLabLanguageServerProcessProvider(
        languageServerWrapper,
        configurationService,
        openFilesService,
        proxyManager,
        webviewService,
        installer,
        {
          spawnThreads += Thread.currentThread()
          FakeLanguageServerProcess(InitializeReply.SUCCESS).also { fake -> spawnedProcesses += fake }
        },
      )
      val caller = Thread({ provider.start(bundle) }, "fake-activator")

      caller.start()
      gate.entered.await(5, TimeUnit.SECONDS) shouldBe true
      caller.join(5_000)
      caller.isAlive shouldBe false
      gate.release.countDown()
      eventually(5.seconds) { spawnedProcesses shouldHaveSize 1 }

      gate.threads.single().name shouldBe LANGUAGE_SERVER_START_THREAD_NAME
      gate.threads.single().isDaemon shouldBe true
      spawnThreads.single() shouldBe gate.threads.single()
      provider.stop()
    }

    it("a queued start of an earlier generation does nothing once a later start is queued (M3)") {
      val store = LanguageServerLifecycleStore()
      val executor = ManualExecutor()
      val provider = newProvider(startExecutor = executor, store = store)
      provider.start(bundle)
      provider.stop()
      provider.start(bundle)

      executor.runNext()

      spawnedProcesses shouldHaveSize 0
      executor.runNext()
      spawnedProcesses shouldHaveSize 1
      eventually(5.seconds) { store.snapshot().phase shouldBe LanguageServerPhase.RUNNING }
      provider.stop()
    }

    it("lets nothing escape the start task when the start throws after the spawn (M8)") {
      val store = LanguageServerLifecycleStore()
      val wrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
      every { wrapper.registerLanguageServer(any()) } throws IllegalStateException("boom")
      every { wrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
      val executor = EscapeRecorder(startExecutor())
      val provider = newProvider(startExecutor = executor, store = store, wrapper = wrapper)

      provider.start(bundle)
      executor.done.await(5, TimeUnit.SECONDS) shouldBe true

      executor.escaped shouldBe emptyList()
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
      spawnedProcesses.single().destroyed shouldBe true
      provider.isRunning shouldBe false
    }

    it("lets nothing escape the start task when its clean-up throws too (M15b)") {
      val store = LanguageServerLifecycleStore()
      val wrapper = mockk<GitLabLanguageServerWrapper>(relaxUnitFun = true)
      every { wrapper.registerLanguageServer(any()) } throws IllegalStateException("boom")
      every { wrapper.unregisterLanguageServer(any<LanguageServerHandle>()) } returns true
      // The first step of the stop: a late or degraded stop whose Koin scope is already closed.
      every { wrapper.unregisterLanguageServer() } throws IllegalStateException("Koin scope is already closed")
      val executor = EscapeRecorder(startExecutor())
      val provider = newProvider(startExecutor = executor, store = store, wrapper = wrapper)

      provider.start(bundle)
      executor.done.await(5, TimeUnit.SECONDS) shouldBe true

      executor.escaped shouldBe emptyList()
      store.snapshot().phase shouldBe LanguageServerPhase.FAILED
    }

    it("starts again after a stop, on the same executor (M12)") {
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(startExecutor = startExecutor(), store = store)
      provider.start(bundle)
      eventually(5.seconds) { store.snapshot().phase shouldBe LanguageServerPhase.RUNNING }

      provider.stop()
      provider.start(bundle)

      eventually(5.seconds) { spawnedProcesses shouldHaveSize 2 }
      eventually(5.seconds) { store.snapshot().phase shouldBe LanguageServerPhase.RUNNING }
      provider.stop()
    }

    it("lets a restart's install abandon its lock wait once the bundle stops (R1)") {
      val checks = CopyOnWriteArrayList<() -> Boolean>()
      every { installer.install(any()) } answers {
        checks += firstArg<() -> Boolean>()
        "/fake/language-server"
      }
      val store = LanguageServerLifecycleStore()
      val provider = newProvider(startExecutor = ManualExecutor(), store = store)

      provider.restart(bundle) shouldBe true
      val check = checks.single()
      check() shouldBe false
      store.close()

      check() shouldBe true
      provider.stop()
    }

    it("a restart whose install is abandoned as the bundle stops returns false without reporting a failure (S-1)") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val store = LanguageServerLifecycleStore()
      // The bundle stops during the install, which then gives up its lock wait and returns null.
      every { installer.install(any()) } answers {
        store.close()
        null
      }
      val provider = newProvider(startExecutor = ManualExecutor(), store = store)

      provider.restart(bundle) shouldBe false

      spawnedProcesses shouldHaveSize 0
      verify(exactly = 0) { log.error(any<String>()) }
      verify(exactly = 0) { log.error(any<String>(), any<Throwable>()) }
    }
  }
})
