package com.gitlab.eclipse

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.chat.quickchat.QuickChatRuntimeLifecycle
import com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry
import com.gitlab.eclipse.ci.joblog.JobLogKey
import com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.status.DuoStatusRefresher
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerFailureKind
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleListener
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleStore
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerPhase
import com.gitlab.eclipse.lsp.lifecycle.StartOrigin
import com.gitlab.eclipse.lsp.messages.LanguageServerMessagePresenter
import com.gitlab.eclipse.mergerequests.diff.MrFileDiffRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.security.SecurityScanSaveListener
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.isActive
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import java.util.concurrent.Executors
import kotlin.reflect.KClass

/**
 * The bundle's stop path, which is the last chance this plugin's diagnostics have to clean up.
 *
 * The steps before the clean up are ordinary service calls that can raise on a late or degraded
 * stop — Koin's scope may already be closed — and each of them sits *upstream* of work that must
 * happen: the markers this plugin published would otherwise survive in the Problems view, and the
 * save listener would stay attached to every `IDocumentProvider` it reached, all of which outlive
 * this bundle. So the property under test is not "stop does its work" but "stop does its work even
 * when an earlier step throws".
 */
class GitLabEclipseStartupTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val processProvider = mockk<GitLabLanguageServerProcessProvider>()
  val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
  val saveListener = mockk<SecurityScanSaveListener>(relaxUnitFun = true)
  val codeSuggestionsManager = mockk<CodeSuggestionsManager>(relaxed = true)
  val oAuthTokenProvider = mockk<OAuthTokenProvider>(relaxed = true)
  val httpClient = mockk<GitLabHttpClient>(relaxed = true)
  val messagePresenter = mockk<LanguageServerMessagePresenter>(relaxed = true)
  val statusRefresher = mockk<DuoStatusRefresher>(relaxed = true)

  /** A fresh, open store per test; resolved through a factory so every lookup sees the current one. */
  var lifecycleStore = LanguageServerLifecycleStore()

  /** When set, resolving the store fails, as a Koin definition that cannot be built does. */
  var lifecycleStoreFails = false

  /** The container stop() resolves from, optionally without [omit] (a definition stop cannot resolve). */
  fun startTestKoin(omit: KClass<*>? = null) {
    startKoin {
      modules(
        module {
          if (omit != GitLabLanguageServerProcessProvider::class) {
            single<GitLabLanguageServerProcessProvider> { processProvider }
          }
          single<DiagnosticMarkerService> { markerService }
          single<SecurityScanSaveListener> { saveListener }
          if (omit != CodeSuggestionsManager::class) single<CodeSuggestionsManager> { codeSuggestionsManager }
          if (omit != OAuthTokenProvider::class) single<OAuthTokenProvider> { oAuthTokenProvider }
          if (omit != GitLabHttpClient::class) single<GitLabHttpClient> { httpClient }
          factory<LanguageServerLifecycleStore> {
            check(!lifecycleStoreFails) { "store definition failed" }
            lifecycleStore
          }
          single<LanguageServerMessagePresenter> { messagePresenter }
          single<DuoStatusRefresher> { statusRefresher }
        }
      )
    }
  }

  fun resetRegistries() {
    DiagnosticGenerationRegistry.resetForTest()
    JobLogGenerationRegistry.resetForTest()
    CiLintGenerationRegistry.resetForTest()
    DiscussionGenerationRegistry.resetForTest()
    MrFileDiffRegistry.resetForTest()
  }

  /** A UI turn that runs the block inline, as syncExec does when stop() is already on the UI thread. */
  val inlineTurn = UiTurn { block ->
    block()
    true
  }

  fun startup(turn: UiTurn, uiSteps: List<LifecycleStep>? = null): GitLabEclipseStartup =
    GitLabEclipseStartup().apply {
      uiTurn = turn
      if (uiSteps != null) uiShutdownSteps = uiSteps
    }

  fun registriesActive(): List<Boolean> = listOf(
    CiLintGenerationRegistry.active,
    DiscussionGenerationRegistry.active,
    JobLogGenerationRegistry.active,
  )

  /** The steps downstream of the UI turn all ran. */
  fun verifyLaterStepsRan() {
    verify { processProvider.stop() }
    verify { markerService.deleteAllMarkers() }
    verify { saveListener.uninstall() }
    verify { codeSuggestionsManager.endAllSessions() }
    verify { httpClient.close() }
  }

  beforeSpec { startTestKoin() }

  beforeEach {
    clearMocks(
      processProvider,
      markerService,
      saveListener,
      codeSuggestionsManager,
      oAuthTokenProvider,
      httpClient,
      messagePresenter,
      statusRefresher,
    )
    lifecycleStore = LanguageServerLifecycleStore()
    lifecycleStoreFails = false
    LifecycleListenerRegistrations.closeAll()
    every { processProvider.stop() } returns Unit
  }

  afterEach { resetRegistries() }

  afterSpec {
    stopKoin()
    // All of them are process-wide objects this path writes to.
    resetRegistries()
  }

  describe("stop") {
    it("completes the diagnostics shutdown even when stopping the language server throws") {
      DiagnosticGenerationRegistry.resetForTest()
      // What a Koin scope that is already closed, or a language server that is half gone, looks
      // like from here.
      every { processProvider.stop() } throws IllegalStateException("Koin scope is already closed")

      shouldNotThrowAny { GitLabEclipseStartup().stop(mockk<BundleContext>()) }

      // Everything downstream of the throw still ran.
      DiagnosticGenerationRegistry.active shouldBe false
      verify { markerService.deleteAllMarkers() }
      verify { saveListener.uninstall() }
    }

    it("stops the OAuth refresh timer even when a later step throws") {
      // Earlier tests in this spec stopped the timer too.
      clearMocks(oAuthTokenProvider)
      every { processProvider.stop() } returns Unit
      every { codeSuggestionsManager.endAllSessions() } throws IllegalStateException("editor already disposed")
      try {
        runCatching { GitLabEclipseStartup().stop(mockk<BundleContext>()) }

        verify { oAuthTokenProvider.stopTokenRefreshTimer() }
      } finally {
        every { codeSuggestionsManager.endAllSessions() } returns Unit
      }
    }
  }

  describe("stop and Quick Chat") {
    it("closes the Quick Chat runtime this activation created") {
      every { processProvider.stop() } returns Unit
      val runtime = QuickChatRuntimeLifecycle.create()

      shouldNotThrowAny { GitLabEclipseStartup().stop(mockk<BundleContext>()) }

      runtime.scope.isActive shouldBe false
      QuickChatRuntimeLifecycle.current.shouldBeNull()
    }

    it("does not create a Quick Chat runtime when none was created") {
      every { processProvider.stop() } returns Unit
      QuickChatRuntimeLifecycle.current.shouldBeNull()

      shouldNotThrowAny { GitLabEclipseStartup().stop(mockk<BundleContext>()) }

      QuickChatRuntimeLifecycle.current.shouldBeNull()
    }
  }

  describe("stop: the UI turn (#62)") {
    it("deactivates the job-log registry inside the UI turn, not before it on the stop thread") {
      val seen = mutableListOf<Boolean>()
      val turn = UiTurn { block ->
        seen += JobLogGenerationRegistry.active
        block()
        seen += JobLogGenerationRegistry.active
        true
      }

      shouldNotThrowAny { startup(turn).stop(mockk<BundleContext>()) }

      seen shouldContainExactly listOf(true, false)
    }

    it("writes the flag on the thread that runs the UI turn") {
      val ui = Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }
      val seen = mutableListOf<String>()
      try {
        val turn = UiTurn { block ->
          seen += "stop thread before: ${JobLogGenerationRegistry.active}"
          ui.submit {
            seen += "${Thread.currentThread().name} before: ${JobLogGenerationRegistry.active}"
            block()
            seen += "${Thread.currentThread().name} after: ${JobLogGenerationRegistry.active}"
          }.get()
          true
        }

        startup(turn).stop(mockk<BundleContext>())
      } finally {
        ui.shutdownNow()
      }

      seen shouldContainExactly listOf(
        "stop thread before: true",
        "fake-ui before: true",
        "fake-ui after: false",
      )
      registriesActive() shouldBe listOf(false, false, false)
    }

    listOf(
      "the workbench is unavailable" to UiTurn { throw IllegalStateException("Workbench has not been created yet.") },
      "the display is disposed" to UiTurn { false },
      "the display is disposed while waiting" to UiTurn { throw SWTException(SWT.ERROR_DEVICE_DISPOSED) },
      "the UI turn fails otherwise" to UiTurn { throw SWTException(SWT.ERROR_FAILED_EXEC) },
    ).forEach { (situation, turn) ->
      it("when $situation: does not throw, leaves every registry active (inert) and runs the later steps") {
        shouldNotThrowAny { startup(turn).stop(mockk<BundleContext>()) }

        registriesActive() shouldBe listOf(true, true, true)
        verifyLaterStepsRan()
      }
    }

    it("a throwing job-log editor step does not skip the merged-YAML editor step") {
      val ran = mutableListOf<String>()
      val steps = listOf(
        LifecycleStep("JobLog editors") { throw IllegalArgumentException("not an SWT failure") },
        LifecycleStep("Merged YAML editors") { ran += "merged" },
      )

      shouldNotThrowAny { startup(inlineTurn, steps).stop(mockk<BundleContext>()) }

      ran shouldContainExactly listOf("merged")
      verifyLaterStepsRan()
    }

    it("a throwing last step does not make stop throw, and the later steps run") {
      val steps = listOf(
        LifecycleStep("JobLog editors") {},
        LifecycleStep("Merged YAML editors") { throw IllegalStateException("workbench gone") },
      )

      shouldNotThrowAny { startup(inlineTurn, steps).stop(mockk<BundleContext>()) }

      verifyLaterStepsRan()
    }

    it("runs the flags first, then popups, sessions and editors, in this order") {
      GitLabEclipseStartup.productionUiShutdownSteps().map { it.name } shouldContainExactly listOf(
        "CI lint registry",
        "Discussion registry",
        "Job log registry",
        "MR thread popups",
        "Quick Chat popups",
        "Review sessions",
        "Job log editors",
        "Merged YAML editors",
        "MR diff editors",
        "Agent Platform views",
        "Lifecycle listeners",
        "Duo status refresher",
        "Current-branch state",
      )
    }

    it("the production steps stop the MR diff registry") {
      shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }

      MrFileDiffRegistry.active shouldBe false
      verifyLaterStepsRan()
    }

    it("the production steps deactivate all three registries") {
      shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }

      registriesActive() shouldBe listOf(false, false, false)
      verifyLaterStepsRan()
    }
  }

  describe("stop: the steps after the UI turn") {
    it("closes the HTTP client even when ending the code suggestion sessions throws") {
      every { codeSuggestionsManager.endAllSessions() } throws IllegalStateException("editor already disposed")

      shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }

      verify { httpClient.close() }
    }

    it("does not throw when closing the HTTP client throws") {
      every { httpClient.close() } throws IllegalStateException("already closed")

      shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }
    }

    listOf(
      OAuthTokenProvider::class,
      GitLabLanguageServerProcessProvider::class,
      CodeSuggestionsManager::class,
    ).forEach { missing ->
      it("continues past a ${missing.simpleName} that cannot be resolved") {
        stopKoin()
        startTestKoin(omit = missing)
        try {
          shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }

          verify { markerService.deleteAllMarkers() }
          verify { saveListener.uninstall() }
          verify { httpClient.close() }
        } finally {
          stopKoin()
          startTestKoin()
        }
      }
    }

    it("continues past a GitLabHttpClient that cannot be resolved, after the earlier steps ran") {
      stopKoin()
      startTestKoin(omit = GitLabHttpClient::class)
      try {
        shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }

        verify { oAuthTokenProvider.stopTokenRefreshTimer() }
        verify { processProvider.stop() }
        verify { markerService.deleteAllMarkers() }
        verify { saveListener.uninstall() }
        verify { codeSuggestionsManager.endAllSessions() }
        verify(exactly = 0) { httpClient.close() }
      } finally {
        stopKoin()
        startTestKoin()
      }
    }

    it("is safe to run twice") {
      val startup = startup(inlineTurn)

      shouldNotThrowAny {
        startup.stop(mockk<BundleContext>())
        startup.stop(mockk<BundleContext>())
      }

      registriesActive() shouldBe listOf(false, false, false)
      verify(exactly = 2) { oAuthTokenProvider.stopTokenRefreshTimer() }
      verify(exactly = 2) { processProvider.stop() }
      verify(exactly = 2) { httpClient.close() }
    }
  }

  describe("stop: what a failure logs") {
    it("names the exception class, never its message, and never hands the throwable to the log") {
      val log = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      every { log.info(capture(messages)) } returns Unit
      every { log.warn(capture(messages)) } returns Unit
      every { log.error(capture(messages)) } returns Unit
      every { Platform.getLog(any<Bundle>()) } returns log
      every { codeSuggestionsManager.endAllSessions() } throws IllegalStateException("secret-token-123")
      every { processProvider.stop() } throws UnsupportedOperationException("https://secret.example/")
      val turn = UiTurn { throw IllegalStateException("workbench secret-token-456") }

      shouldNotThrowAny { startup(turn).stop(mockk<BundleContext>()) }

      messages.shouldNotBeEmpty()
      messages.joinToString("\n").let { all ->
        all shouldContain "IllegalStateException"
        all shouldContain "UnsupportedOperationException"
        all shouldNotContain "secret"
      }
      verify(exactly = 0) { log.warn(any(), any()) }
      verify(exactly = 0) { log.info(any(), any()) }
      verify(exactly = 0) { log.error(any(), any()) }
    }

    it("contains a log call that throws itself") {
      every { Platform.getLog(any<Bundle>()) } throws IllegalStateException("no log")
      every { codeSuggestionsManager.endAllSessions() } throws IllegalStateException("boom")

      shouldNotThrowAny { startup(inlineTurn).stop(mockk<BundleContext>()) }

      verify { httpClient.close() }
    }
  }

  describe("stop -> start: re-activating the registries") {
    it("brings all three registries back and strands the generations numbered before the stop") {
      val key = JobLogKey("conn", 1L, 2L)
      val preStop = JobLogGenerationRegistry.nextGeneration(key)
      val startup = startup(inlineTurn)
      startup.stop(mockk<BundleContext>())
      registriesActive() shouldBe listOf(false, false, false)
      // The production steps, except the review-session hooks: installing them in a unit test
      // would leave process-wide callbacks behind.
      startup.activationSteps = GitLabEclipseStartup.productionActivationSteps()
        .map { step -> if (step.name == "MR thread popups") LifecycleStep(step.name) {} else step }

      shouldNotThrowAny { startup.activateGenerationRegistries() }

      registriesActive() shouldBe listOf(true, true, true)
      MrFileDiffRegistry.active shouldBe true
      JobLogGenerationRegistry.shouldAct(key, preStop) shouldBe false
    }

    it("a throwing step does not keep the job-log registry down") {
      JobLogGenerationRegistry.onDeactivate()
      val startup = startup(inlineTurn)
      startup.activationSteps = listOf(
        LifecycleStep("MR thread popups") { throw IllegalStateException("no display") },
        LifecycleStep("Job log registry") { JobLogGenerationRegistry.onActivate() },
      )

      shouldNotThrowAny { startup.activateGenerationRegistries() }

      JobLogGenerationRegistry.active shouldBe true
    }

    it("does not throw when the UI turn cannot be reached") {
      val startup = startup(UiTurn { throw IllegalStateException("Workbench has not been created yet.") })

      shouldNotThrowAny { startup.activateGenerationRegistries() }
    }

    it("activates the registries before installing the review-session hooks") {
      GitLabEclipseStartup.productionActivationSteps().map { it.name } shouldContainExactly listOf(
        "CI lint registry",
        "Discussion registry",
        "Job log registry",
        "MR diff registry",
        "MR thread popups",
        "Agent Platform views",
      )
    }
  }
  describe("the language server lifecycle wiring (LS-02, LS-08, QCHAT-07)") {
    /** A listener that records every transition it sees as "<name>:<phase>". */
    fun recording(name: String, seen: MutableList<String>) =
      name to { LanguageServerLifecycleListener { _, new -> seen += "$name:${new.phase}" } }

    fun wired(seen: MutableList<String>, dispatched: MutableList<Runnable> = mutableListOf()) =
      startup(inlineTurn).apply {
        lifecycleListeners = listOf(recording("notifier", seen), recording("refresher", seen))
        uiDispatch = { dispatched += it }
      }

    it("attaches the listeners before the start, so failures reach them, even those the start thread publishes") {
      val seen = mutableListOf<String>()
      every { processProvider.start(any()) } answers {
        seen += "provider.start"
        val generation = lifecycleStore.beginAttempt(StartOrigin.STARTUP)
        lifecycleStore.markFailed(generation, LanguageServerFailureKind.INSTALL_FAILED)
      }

      wired(seen).startLanguageServer(mockk<Bundle>())

      seen shouldContainExactly listOf(
        "provider.start",
        "notifier:STARTING",
        "refresher:STARTING",
        "notifier:FAILED",
        "refresher:FAILED",
      )
    }

    it("a listener that cannot be built skips neither the later listeners nor the start") {
      val seen = mutableListOf<String>()
      every { processProvider.start(any()) } answers {
        seen += "provider.start"
        lifecycleStore.beginAttempt(StartOrigin.STARTUP)
      }
      val startup = wired(seen).apply {
        lifecycleListeners = listOf(
          recording("notifier", seen),
          "broken" to { throw IllegalStateException("definition failed") },
          recording("refresher", seen),
        )
      }

      shouldNotThrowAny { startup.startLanguageServer(mockk<Bundle>()) }

      seen shouldContainExactly listOf("provider.start", "notifier:STARTING", "refresher:STARTING")
    }

    it("a lifecycle store that cannot be resolved does not keep the language server from starting") {
      lifecycleStoreFails = true
      every { processProvider.start(any()) } returns Unit

      shouldNotThrowAny { wired(mutableListOf()).startLanguageServer(mockk<Bundle>()) }

      verify { processProvider.start(any()) }
    }

    it("an unexpected failure of the UI hop is contained") {
      every { processProvider.start(any()) } returns Unit
      val startup = wired(mutableListOf()).apply { uiDispatch = { throw UnsupportedOperationException("hop") } }

      shouldNotThrowAny { startup.startLanguageServer(mockk<Bundle>()) }
    }

    it("hands the Duo status steps to the UI thread asynchronously, after the language server start was requested") {
      val seen = mutableListOf<String>()
      val dispatched = mutableListOf<Runnable>()
      every { processProvider.start(any()) } answers { seen += "provider.start" }
      val startup = wired(seen, dispatched).apply {
        duoStatusActivationSteps = listOf(LifecycleStep("Duo status refresher") { seen += "ui step" })
      }

      startup.startLanguageServer(mockk<Bundle>())
      seen shouldContainExactly listOf("provider.start")

      dispatched.single().run()
      seen shouldContainExactly listOf("provider.start", "ui step")
    }

    it("does not throw when the UI thread cannot be reached, and still starts the language server") {
      every { processProvider.start(any()) } returns Unit
      val startup = wired(mutableListOf()).apply {
        uiDispatch = { throw IllegalStateException("Workbench has not been created yet.") }
      }

      shouldNotThrowAny { startup.startLanguageServer(mockk<Bundle>()) }

      verify { processProvider.start(any()) }
    }

    it("the production Duo status step installs the window listeners, then requests one refresh (F7)") {
      GitLabEclipseStartup.productionDuoStatusActivationSteps().forEach { it.run() }

      verifyOrder {
        statusRefresher.installWindowListeners()
        statusRefresher.request()
      }
    }

    it("the post-start UI steps install the Duo status refresher, then the current-branch state (status-bar §4.1)") {
      GitLabEclipseStartup.productionDuoStatusActivationSteps().map { it.name } shouldContainExactly listOf(
        "Duo status refresher",
        "Current-branch state",
      )
    }

    it("the production listeners are the failure notifier, the status refresher and the message presenter") {
      GitLabEclipseStartup.productionLifecycleListeners().map { it.first } shouldContainExactly listOf(
        "Language server failure notifier",
        "Duo status refresher",
        "Language server messages",
      )
    }

    it("stop closes the lifecycle store before it stops the language server (design §5.5)") {
      val seen = mutableListOf<String>()
      every { processProvider.stop() } answers { seen += "provider.stop closed=${lifecycleStore.isClosed}" }
      every { messagePresenter.sessionsEndedExcept(null) } answers {
        seen += "sessions ended closed=${lifecycleStore.isClosed}"
      }

      startup(inlineTurn).stop(mockk<BundleContext>())

      seen shouldContainExactly listOf("sessions ended closed=true", "provider.stop closed=true")
    }

    it("after stop, a provider transition reaches no listener") {
      val seen = mutableListOf<String>()
      every { processProvider.start(any()) } returns Unit
      val startup = wired(seen)
      startup.startLanguageServer(mockk<Bundle>())

      startup.stop(mockk<BundleContext>())
      lifecycleStore.beginAttempt(StartOrigin.RESTART)

      seen.shouldBeEmpty()
    }

    it("the shutdown UI turn removes the listeners and disposes the status refresher (I6)") {
      val seen = mutableListOf<String>()
      every { processProvider.start(any()) } returns Unit
      wired(seen).startLanguageServer(mockk<Bundle>())

      GitLabEclipseStartup.productionUiShutdownSteps()
        .filter { it.name == "Lifecycle listeners" || it.name == "Duo status refresher" }
        .forEach { it.run() }
      // The store itself is still open here: only the listener removal can keep this silent.
      lifecycleStore.beginAttempt(StartOrigin.RESTART)

      seen.shouldBeEmpty()
      verify { statusRefresher.dispose() }
      lifecycleStore.snapshot().phase shouldBe LanguageServerPhase.STARTING
    }
  }
})
