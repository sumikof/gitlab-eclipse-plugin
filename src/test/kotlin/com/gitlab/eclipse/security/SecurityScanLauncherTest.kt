package com.gitlab.eclipse.security

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.eclipse.ui.preferences.ScopedPreferenceStore

private const val URI_A = "file:/w/a.kt"
private const val KEY_A = "/w/a.kt"

class SecurityScanLauncherTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("runSecurityScan gates") {
    it("sends nothing and notifies nothing when the feature is disabled") {
      var sent = 0
      var notified = 0
      runSecurityScan(
        "file:/a.kt", SecurityScanSource.COMMAND, enabled = false, hasToken = true,
        send = { sent++ }, notify = { notified++ }
      ) shouldBe SecurityScanLaunchOutcome.DISABLED
      sent shouldBe 0
      notified shouldBe 0
    }

    it("reports NO_EDITOR and notifies only for COMMAND") {
      var notified = 0
      runSecurityScan(null, SecurityScanSource.COMMAND, true, true, {}, { notified++ }) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
      notified shouldBe 1
      notified = 0
      runSecurityScan(null, SecurityScanSource.SAVE, true, true, {}, { notified++ }) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
      notified shouldBe 0
    }

    it("reports NO_TOKEN and notifies only for COMMAND") {
      var notified = 0
      runSecurityScan("file:/a.kt", SecurityScanSource.COMMAND, true, false, {}, { notified++ }) shouldBe
        SecurityScanLaunchOutcome.NO_TOKEN
      notified shouldBe 1
      notified = 0
      runSecurityScan("file:/a.kt", SecurityScanSource.SAVE, true, false, {}, { notified++ }) shouldBe
        SecurityScanLaunchOutcome.NO_TOKEN
      notified shouldBe 0
    }

    it("never invokes send on any non-SENT path") {
      var sent = 0
      runSecurityScan("file:/a.kt", SecurityScanSource.COMMAND, false, true, { sent++ }, {})
      runSecurityScan("file:/a.kt", SecurityScanSource.SAVE, false, true, { sent++ }, {})
      runSecurityScan(null, SecurityScanSource.COMMAND, true, true, { sent++ }, {})
      runSecurityScan(null, SecurityScanSource.SAVE, true, true, { sent++ }, {})
      runSecurityScan("file:/a.kt", SecurityScanSource.COMMAND, true, false, { sent++ }, {})
      runSecurityScan("file:/a.kt", SecurityScanSource.SAVE, true, false, { sent++ }, {})
      sent shouldBe 0
    }

    it("sends exactly once with the expected params on the happy path") {
      val sent = mutableListOf<SecurityScanParams>()
      runSecurityScan("file:/a.kt", SecurityScanSource.SAVE, true, true, { sent += it }, {}) shouldBe
        SecurityScanLaunchOutcome.SENT
      sent.single() shouldBe SecurityScanParams("file:/a.kt", "save")
    }

    it("labels a command triggered scan with the command wire value") {
      val sent = mutableListOf<SecurityScanParams>()
      runSecurityScan("file:/a.kt", SecurityScanSource.COMMAND, true, true, { sent += it }, {}) shouldBe
        SecurityScanLaunchOutcome.SENT
      sent.single() shouldBe SecurityScanParams("file:/a.kt", "command")
    }

    it("evaluates the gates in the order enabled -> uri -> hasToken") {
      var notified = 0
      // Disabled AND no editor AND no token -> DISABLED wins and nothing is shown.
      runSecurityScan(null, SecurityScanSource.COMMAND, false, false, {}, { notified++ }) shouldBe
        SecurityScanLaunchOutcome.DISABLED
      notified shouldBe 0
      // No editor AND no token -> the editor gate wins.
      runSecurityScan(null, SecurityScanSource.COMMAND, true, false, {}, {}) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
    }

    it("treats a blank uri as no editor") {
      runSecurityScan("   ", SecurityScanSource.COMMAND, true, true, {}, {}) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
    }
  }

  describe("SecurityScanLauncher") {
    val preferenceStore = mockk<ScopedPreferenceStore>(relaxed = true)
    val wrapper = mockk<GitLabLanguageServerWrapper>()
    val configurationService = mockk<GitLabLanguageServerConfigurationService>()
    val tokenManager = mockk<GitLabTokenProviderManager>()
    val server = mockk<GitLabLanguageServer>(relaxUnitFun = true)
    val notified = mutableListOf<String>()

    fun enable(value: Boolean) {
      every { preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED) } returns value
    }

    fun launcher(scope: CoroutineScope, outboundLock: Mutex = Mutex()) = SecurityScanLauncher(
      preferenceStore,
      wrapper,
      configurationService,
      tokenManager,
      scope,
      outboundLock,
    ) { notified += it }

    beforeEach {
      DiagnosticGenerationRegistry.resetForTest()
      CommandWaiters.resetForTest()
      notified.clear()
      // These mocks live for the whole spec, so their recorded calls have to go: a
      // `verify(exactly = 0)` would otherwise see what an earlier test sent.
      clearMocks(preferenceStore, wrapper, configurationService, tokenManager, server)
      every { wrapper.languageServer } returns server
      every { tokenManager.getToken() } returns "token"
      every { configurationService.buildParams() } returns GitLabLanguageServerConfigurationParams()
      enable(true)
    }

    afterEach {
      CommandWaiters.resetForTest()
      DiagnosticGenerationRegistry.resetForTest()
    }

    fun epoch() = DiagnosticGenerationRegistry.currentEpoch

    it("never touches the language server while the feature is off") {
      enable(false)
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.DISABLED
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.runSecurityScan(any()) }
      verify(exactly = 0) { server.didChangeConfiguration(any()) }
      notified shouldBe emptyList()
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("converges the suspended parity from the setting before it gates") {
      enable(false)
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)

      DiagnosticGenerationRegistry.isSuspended(SECURITY_SCAN_SOURCE) shouldBe true

      enable(true)
      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)

      DiagnosticGenerationRegistry.isSuspended(SECURITY_SCAN_SOURCE) shouldBe false
    }

    it("sends the configuration and then the scan") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      scope.testScheduler.runCurrent()

      verifyOrder {
        server.didChangeConfiguration(any())
        server.runSecurityScan(SecurityScanParams(URI_A, "command"))
      }
    }

    it("keeps both notifications inside one outbound lock region") {
      val scope = TestScope(StandardTestDispatcher())
      val outboundLock = Mutex()
      // Somebody else owns the outbound lock, so neither notification may get out yet.
      outboundLock.tryLock() shouldBe true

      launcher(scope, outboundLock).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.didChangeConfiguration(any()) }
      verify(exactly = 0) { server.runSecurityScan(any()) }

      outboundLock.unlock()
      scope.testScheduler.advanceUntilIdle()

      verifyOrder {
        server.didChangeConfiguration(any())
        server.runSecurityScan(any())
      }
    }

    it("registers a waiter for a command and none for a save") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE) shouldBe SecurityScanLaunchOutcome.SENT
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("reports NO_TOKEN without sending when there is no token") {
      every { tokenManager.getToken() } returns ""
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.NO_TOKEN
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.runSecurityScan(any()) }
      notified.size shouldBe 1
    }

    it("reports NO_EDITOR for a uri that is not a file") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch("untitled:Untitled-1", SecurityScanSource.COMMAND) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.runSecurityScan(any()) }
    }

    it("stops the send when the setting is switched off after the gate passed") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      // The request is queued but has not reached the lock yet. The user turns the feature off.
      enable(false)
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.didChangeConfiguration(any()) }
      verify(exactly = 0) { server.runSecurityScan(any()) }
      // Abandoned on purpose: the waiter is gone and the user is not told anything.
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      notified shouldBe emptyList()
    }

    it("stops the send when the source was suspended after the gate passed") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      DiagnosticGenerationRegistry.suspendSource(
        SECURITY_SCAN_SOURCE, DiagnosticGenerationRegistry.nextSettingsSeq()
      ) shouldBe true
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.runSecurityScan(any()) }
      notified shouldBe emptyList()
    }

    it("cleans up and reports when the coroutine body never runs at all") {
      // A cancelled scope makes launch return a completed job without throwing and without ever
      // entering the body, which no try/catch around the send could observe.
      val scope = CoroutineScope(Dispatchers.Unconfined)
      scope.cancel()

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT

      verify(exactly = 0) { server.runSecurityScan(any()) }
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      notified.size shouldBe 1
    }

    it("stays silent about a save whose coroutine never ran") {
      val scope = CoroutineScope(Dispatchers.Unconfined)
      scope.cancel()

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE) shouldBe SecurityScanLaunchOutcome.SENT

      notified shouldBe emptyList()
    }

    it("cleans up and reports when there is no language server to send to") {
      every { wrapper.languageServer } returns null
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      scope.testScheduler.advanceUntilIdle()

      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      notified.size shouldBe 1
    }

    it("keeps quiet on the completion path of a successful send") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()

      notified shouldBe emptyList()
      // The waiter survives the send: it is the response that gets to claim it.
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("tells the user once the answer deadline passes") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      notified shouldBe emptyList()

      scope.testScheduler.advanceUntilIdle()

      notified.size shouldBe 1
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("lets a deadline expire quietly once the response has claimed the waiter") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND

      scope.testScheduler.advanceUntilIdle()

      notified shouldBe emptyList()
    }

    it("does not let one request's deadline cancel the next request on the same file") {
      val scope = TestScope(StandardTestDispatcher())
      val subject = launcher(scope)

      subject.launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      // The first scan is answered well before its deadline.
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND

      // Half a minute later the user starts a second scan of the same file. Its own deadline is
      // therefore still half a minute away when the first one comes due.
      scope.testScheduler.advanceTimeBy(30_000L)
      subject.launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()

      // The first deadline fires here, and only that one. The second scan must survive it.
      scope.testScheduler.advanceTimeBy(31_000L)

      notified shouldBe emptyList()
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("strands a request with the connection it was sent on") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      // The server dies before the answer arrives; the waiter belongs to the dead connection.
      DiagnosticGenerationRegistry.onServerStopped()

      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      CommandWaiters.clear(epoch() - 1) shouldNotBe emptyMap<String, Int>()
    }
  }
})
