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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val URI_A = "file:/w/a.kt"
private const val KEY_A = "/w/a.kt"

/**
 * The disabled gate's fixed wording, spelled out rather than imported.
 *
 * The production constant is file-private, and deliberately: a test that shared the constant would
 * pass whatever it was changed to, and this is user-facing text on the one path a user reaches
 * before opting in.
 */
private const val DISABLED_TEXT =
  "GitLab security scan is turned off. Enable real-time SAST scan in the GitLab preferences to " +
    "run it."

class SecurityScanLauncherTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("runSecurityScan gates") {
    it("sends nothing and tells a command the feature is off") {
      // F6: the setting defaults to off, so this gate is what the very first press of the menu item
      // hits. Showing nothing there is indistinguishable from a broken command.
      var sent = 0
      val notified = mutableListOf<String>()
      runSecurityScan(
        "file:/a.kt", SecurityScanSource.COMMAND, enabled = false, hasToken = true,
        send = { sent++ }, notify = { notified += it }
      ) shouldBe SecurityScanLaunchOutcome.DISABLED
      sent shouldBe 0
      notified.single() shouldBe DISABLED_TEXT
    }

    it("sends nothing and notifies nothing for a save while the feature is disabled") {
      // The half that must not move. A user who never opted in would otherwise be told about this
      // feature on every single save.
      var sent = 0
      var notified = 0
      runSecurityScan(
        "file:/a.kt", SecurityScanSource.SAVE, enabled = false, hasToken = true,
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
      val notified = mutableListOf<String>()
      // Disabled AND no editor AND no token -> DISABLED wins, and the message proves it: the two
      // later gates would each have produced a different one.
      runSecurityScan(null, SecurityScanSource.COMMAND, false, false, {}, { notified += it }) shouldBe
        SecurityScanLaunchOutcome.DISABLED
      notified.single() shouldBe DISABLED_TEXT
      // No editor AND no token -> the editor gate wins.
      runSecurityScan(null, SecurityScanSource.COMMAND, true, false, {}, {}) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
    }

    it("treats a blank uri as no editor") {
      runSecurityScan("   ", SecurityScanSource.COMMAND, true, true, {}, {}) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
    }
  }

  describe("schedulePlatformDeadline") {
    // The production scheduler. It runs on a platform Job rather than the shared CoroutineScope,
    // where an uncaught failure would cancel the scope and silently disable every later launch in
    // the plugin. Its contract is that `onDue` runs even when the delay never elapses.
    fun pendingDeadlines() = Job.getJobManager().find(null).filter { it.name == DEADLINE_JOB }

    afterEach { pendingDeadlines().forEach { it.cancel() } }

    it("runs the body from the job itself and again from its completion listener") {
      // Counting to two is the point. A single count would be satisfied by either call site alone,
      // so it would not notice the job body losing its call — and the body is the normal path,
      // the one that actually reports a scan that timed out.
      val ran = CountDownLatch(2)

      schedulePlatformDeadline(1L) { ran.countDown() }

      ran.await(10, TimeUnit.SECONDS) shouldBe true
    }

    it("runs the body anyway when the job is cancelled before it can run") {
      // What the platform does at shutdown. Without this the command that armed the deadline would
      // wait for an answer that can no longer come, and never be told.
      val ran = CountDownLatch(1)

      schedulePlatformDeadline(600_000L) { ran.countDown() }
      pendingDeadlines().forEach { it.cancel() }

      ran.await(10, TimeUnit.SECONDS) shouldBe true
    }

    it("runs the body anyway when the platform refuses to schedule the job") {
      // A negative delay makes JobManager reject the schedule outright. It rejects every schedule
      // the same way, with "Job manager has been shut down.", once the workbench is stopping —
      // and by then the deadline is already marked armed, so nothing else would ever release the
      // waiter.
      val ran = CountDownLatch(1)

      schedulePlatformDeadline(-1L) { ran.countDown() }

      ran.await(10, TimeUnit.SECONDS) shouldBe true
    }

    it("does not let a throwing body escape into the platform") {
      // Observed through the status the platform ends the job with, not through the body: a body
      // that threw would be turned into an error status, which raises a dialog over what is only a
      // background timer. Counting a latch before the throw would prove nothing at all.
      val finished = CountDownLatch(1)
      val results = CopyOnWriteArrayList<IStatus>()
      val listener = object : JobChangeAdapter() {
        override fun done(event: IJobChangeEvent) {
          if (event.job.name != DEADLINE_JOB) return
          results += event.result
          finished.countDown()
        }
      }
      Job.getJobManager().addJobChangeListener(listener)

      try {
        schedulePlatformDeadline(1L) { error("boom") }

        finished.await(10, TimeUnit.SECONDS) shouldBe true
        results.isEmpty() shouldBe false
        results.none { it.severity == IStatus.ERROR } shouldBe true
      } finally {
        Job.getJobManager().removeJobChangeListener(listener)
      }
    }
  }

  describe("SecurityScanLauncher") {
    val preferenceStore = mockk<ScopedPreferenceStore>(relaxed = true)
    val wrapper = mockk<GitLabLanguageServerWrapper>()
    val configurationService = mockk<GitLabLanguageServerConfigurationService>()
    val tokenManager = mockk<GitLabTokenProviderManager>()
    val server = mockk<GitLabLanguageServer>(relaxUnitFun = true)
    val notified = mutableListOf<String>()

    // What the launcher asked to have run after a delay. The real scheduler is a platform Job, so
    // the deadline is captured here instead of being driven by virtual time.
    val deadlines = mutableListOf<Pair<Long, () -> Unit>>()

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
      scheduleDeadline = { delayMs, onDue -> deadlines += delayMs to onDue },
    ) { notified += it }

    beforeEach {
      DiagnosticGenerationRegistry.resetForTest()
      CommandWaiters.resetForTest()
      notified.clear()
      deadlines.clear()
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

    /**
     * Sends a command scan whose send succeeds and whose *record* of it cannot be written.
     *
     * The platform log can be gone while the workbench is stopping, and the "requested" record is
     * now written after the send. A failure escaping there would be caught as if the send itself
     * had thrown, which is the invariant this feature protects, inverted. Each of the three
     * properties that says it was not is a separate case below, so that one of them failing cannot
     * hide the other two.
     */
    fun launchWithADeadLog(scope: TestScope): ILog {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every {
        log.info(match<String> { it.startsWith("Requested a remote GitLab security scan") })
      } throws IllegalStateException("the platform log is gone")

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      scope.testScheduler.runCurrent()

      // The precondition every case below rests on: the request really did leave.
      verify(exactly = 1) { server.runSecurityScan(SecurityScanParams(URI_A, "command")) }
      return log
    }

    it("never touches the language server while the feature is off") {
      enable(false)
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.DISABLED
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.runSecurityScan(any()) }
      verify(exactly = 0) { server.didChangeConfiguration(any()) }
      // The command is answered, but only by the client: nothing reached the server to answer it.
      notified.single() shouldBe DISABLED_TEXT
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("does not even read the token while the feature is off") {
      // Reading the token goes to Equinox secure storage, which can block and can raise the master
      // password prompt. A user who never opted in must not be asked for a password by this
      // feature, so the gate has to win before the argument is even evaluated (A1).
      enable(false)
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.DISABLED
      launcher(scope).launch(URI_A, SecurityScanSource.SAVE) shouldBe SecurityScanLaunchOutcome.DISABLED
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { tokenManager.getToken() }
    }

    it("does not even read the token when there is nothing to scan") {
      // Same defect, one gate later. The token read is an argument at the call site, so it happens
      // before the gates run unless it is short-circuited on *every* gate that precedes it: a
      // command with no active file, or an untitled editor, would otherwise reach Equinox secure
      // storage and could raise the master password prompt on the way to saying "open a file".
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(null, SecurityScanSource.COMMAND) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
      launcher(scope).launch("untitled:Untitled-1", SecurityScanSource.COMMAND) shouldBe
        SecurityScanLaunchOutcome.NO_EDITOR
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { tokenManager.getToken() }
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

    it("records that a request went out, after the send that made it true") {
      // The only record that an opt-in upload of the user's file happened. Nothing else pins it, so
      // an edit could delete it silently and leave the send with no trace at all — and the order is
      // pinned with it, because a line written before the send only claims the attempt.
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      scope.testScheduler.runCurrent()

      verify(exactly = 1) { log.info("Requested a remote GitLab security scan (source=command).") }
      verifyOrder {
        server.runSecurityScan(SecurityScanParams(URI_A, "command"))
        log.info("Requested a remote GitLab security scan (source=command).")
      }
    }

    it("still arms the deadline when only the record of the send could not be written") {
      // Arming is what says "this went out and is somebody else's problem now". Lose it and the
      // completion handler treats a request that really left as one that never did.
      val scope = TestScope(StandardTestDispatcher())

      launchWithADeadLog(scope)

      deadlines.single().first shouldBe 60_000L
      // The waiter survives for the response to claim, as on any other successful send.
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("audits no failure when only the record of the send could not be written") {
      val scope = TestScope(StandardTestDispatcher())

      val log = launchWithADeadLog(scope)

      // A dead log is not a failed scan. An `outcome=failure` line here would be the audit trail
      // saying the opposite of what happened, which is the same defect this record exists against.
      verify(exactly = 0) { log.info(match<String> { it.startsWith("securityScan ") }) }
    }

    it("tells the user nothing when only the record of the send could not be written") {
      val scope = TestScope(StandardTestDispatcher())

      launchWithADeadLog(scope)

      // The command is still waiting for a real answer; its deadline is what will speak if none
      // comes. Reporting a failure now would be wrong and would leave the deadline to report again.
      notified shouldBe emptyList()
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

    it("arms no answer deadline for a save, so a save can never report a timeout") {
      // §11.3 row 7. A save is background work: the user did not ask, and a popup a minute after a
      // save would arrive with no context at all. The silence is structural rather than a check on
      // the trigger — a save registers no waiter, so there is nothing to expire.
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE) shouldBe SecurityScanLaunchOutcome.SENT
      scope.testScheduler.advanceUntilIdle()

      deadlines shouldBe emptyList()
      notified shouldBe emptyList()
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
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
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
      // Nothing left the machine, so the trail must not say it did — not even followed by the line
      // that says it was switched off. The reader is not required to join two lines to get the
      // truth about an upload.
      verify(exactly = 0) {
        log.info(match<String> { it.startsWith("Requested a remote GitLab security scan") })
      }
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
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { wrapper.languageServer } returns null
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT
      scope.testScheduler.advanceUntilIdle()

      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      notified.size shouldBe 1
      // There was nothing to send to, so the trail must not claim anything was sent — the
      // outcome=failure line that follows is a second record, not a correction to this one.
      verify(exactly = 0) {
        log.info(match<String> { it.startsWith("Requested a remote GitLab security scan") })
      }
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

      deadlines.single().first shouldBe 60_000L
      deadlines.single().second()

      // §11.3 row 4. Fixed client-side wording, from the same table every other outcome uses.
      notified.single() shouldBe "GitLab security scan: no response from the language server."
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("audits a timeout in the shared format, with no path of its own") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      deadlines.single().second()

      verify {
        log.info(
          "securityScan source=command outcome=timeout httpStatus=- findings=- " +
            "exceptionType=- path=-"
        )
      }
      verify(exactly = 0) { log.info(match<String> { it.contains(KEY_A) }) }
    }

    it("tells the user a request that never left failed, in the generic fixed wording") {
      // §11.3 row 8 / §11.4. The status is genuinely unknown — nothing was sent, so nothing
      // answered — which is the case the generic message is worded for.
      every { server.runSecurityScan(any()) } throws IllegalStateException("stream closed")
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)

      notified.single() shouldBe
        "GitLab security scan failed (status -). See the Error Log for details."
    }

    it("audits a send that failed with the exception class and never its message") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { server.runSecurityScan(any()) } throws
        IllegalStateException("Bearer glpat-SECRET stream closed")
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)

      verify {
        log.info(
          "securityScan source=command outcome=failure httpStatus=- findings=- " +
            "exceptionType=IllegalStateException path=-"
        )
      }
      verify(exactly = 0) { log.info(match<String> { it.contains("glpat") }) }
      verify(exactly = 0) { log.warn(match<String> { it.contains("glpat") }) }
      // The send threw, so nothing left the machine and the trail must not say it did. This is the
      // dead-stream case the failure line above already covers, and it is the last place the
      // "requested" record could still have been written ahead of the send it claims.
      verify(exactly = 0) {
        log.info(match<String> { it.startsWith("Requested a remote GitLab security scan") })
      }
    }

    it("records nothing about a request whose send threw, for a save either") {
      // The save half of the same property. A save has no waiter, so the failure line below is the
      // only trace it leaves; a "requested" line beside it would be the sole record claiming an
      // upload of the user's file that never happened.
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { server.runSecurityScan(any()) } throws IllegalStateException("stream closed")
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE) shouldBe SecurityScanLaunchOutcome.SENT

      verify(exactly = 0) {
        log.info(match<String> { it.startsWith("Requested a remote GitLab security scan") })
      }
      // The never-sent reporting still happens, so the assertion above is about placement rather
      // than about the record having been dropped altogether.
      verify(exactly = 1) {
        log.info(
          "securityScan source=save outcome=failure httpStatus=- findings=- " +
            "exceptionType=IllegalStateException path=-"
        )
      }
      notified shouldBe emptyList()
    }

    it("audits a save whose send failed without telling the user") {
      // §11.3 row 9: the record is the only trace a background failure leaves.
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { server.runSecurityScan(any()) } throws IllegalStateException("stream closed")
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE)

      verify {
        log.info(
          "securityScan source=save outcome=failure httpStatus=- findings=- " +
            "exceptionType=IllegalStateException path=-"
        )
      }
      notified shouldBe emptyList()
    }

    it("audits nothing for a save that was sent successfully") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE)

      verify(exactly = 0) { log.info(match<String> { it.startsWith("securityScan ") }) }
    }

    it("closes the request out exactly once however often the deadline runs") {
      // The platform scheduler releases the waiter from the job body and again from the job's
      // completion listener, so that a job cancelled before it ever ran still closes the request
      // out. Running twice must not show the message twice.
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()

      val onDue = deadlines.single().second
      onDue()
      onDue()

      notified.size shouldBe 1
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
    }

    it("lets a deadline expire quietly once the response has claimed the waiter") {
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND

      deadlines.single().second()

      notified shouldBe emptyList()
    }

    it("does not let one request's deadline cancel the next request on the same file") {
      val scope = TestScope(StandardTestDispatcher())
      val subject = launcher(scope)

      subject.launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      // The first scan is answered well before its deadline.
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND

      // The user starts a second scan of the same file, whose own deadline is still far off when
      // the first one comes due.
      subject.launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.runCurrent()
      deadlines.size shouldBe 2

      // The first deadline fires here, and only that one. The second scan must survive it.
      deadlines[0].second()

      notified shouldBe emptyList()
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("keeps a failing send from cancelling the shared scope") {
      // The scope is shared with the whole plugin and is built on a plain Job, not a SupervisorJob:
      // a failure that escaped would cancel it, and every later launch anywhere in the plugin would
      // silently do nothing for the rest of the session.
      every { server.runSecurityScan(any()) } throws IllegalStateException("stream closed")
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND) shouldBe SecurityScanLaunchOutcome.SENT

      scope.isActive shouldBe true
      var ranAfterwards = false
      scope.launch { ranAfterwards = true }
      ranAfterwards shouldBe true

      // The command still hears about it, through the same never-sent path as any other failure.
      notified.size shouldBe 1
      CommandWaiters.consumeOldest(KEY_A, epoch()) shouldBe WaiterMatch.NO_WAITER
      deadlines shouldBe emptyList()
    }

    it("keeps a save whose send failed from cancelling the shared scope, silently") {
      every { server.runSecurityScan(any()) } throws IllegalStateException("stream closed")
      val scope = CoroutineScope(Dispatchers.Unconfined)

      launcher(scope).launch(URI_A, SecurityScanSource.SAVE) shouldBe SecurityScanLaunchOutcome.SENT

      scope.isActive shouldBe true
      notified shouldBe emptyList()
    }

    it("abandons a command whose waiter cannot be registered, and tells the user once") {
      // The server restarts between the epoch being read and the waiter being registered, so
      // `CommandWaiters.add` refuses. Sending anyway would put a command-labelled request on the
      // wire with nothing waiting for it: the answer would later be settled as a save, and the user
      // who pressed a button would be told nothing at all (F6).
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { wrapper.languageServer } answers {
        DiagnosticGenerationRegistry.onServerStopped()
        server
      }
      val scope = TestScope(StandardTestDispatcher())

      launcher(scope).launch(URI_A, SecurityScanSource.COMMAND)
      scope.testScheduler.advanceUntilIdle()

      verify(exactly = 0) { server.didChangeConfiguration(any()) }
      verify(exactly = 0) { server.runSecurityScan(any()) }
      // The same fixed wording a restart that caught the request one step later already uses.
      notified.single() shouldBe
        "GitLab security scan: the scan was cancelled because the language server restarted. " +
        "Run the scan again."
      verify(exactly = 1) {
        log.info(
          "securityScan source=command outcome=cancelled httpStatus=- findings=- " +
            "exceptionType=- path=-"
        )
      }
      // The half that makes the assertion above exhaustive rather than merely present. The trail
      // must not also claim the request went out: this record exists to say that an opt-in upload
      // of the user's file happened, so a false positive is the wrong direction for it to err in.
      verify(exactly = 0) {
        log.info(match<String> { it.startsWith("Requested a remote GitLab security scan") })
      }
      deadlines shouldBe emptyList()
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
