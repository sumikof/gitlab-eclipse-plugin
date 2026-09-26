package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.ISchedulingRule
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.jface.operation.IRunnableWithProgress
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PartInitException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * User-facing wording, spelled out rather than imported: the production constants would make a
 * test pass whatever they were changed to (same reasoning as SecurityScanLauncherTest).
 */
private const val CLOSED_TEXT =
  "A project named 'GitLab Duo Tutorial' exists but is closed. Open it and run the command again, or rename it."
private const val NOT_OWNED_TEXT =
  "A project named 'GitLab Duo Tutorial' already exists and was not created by GitLab. Rename it to use the tutorial."
private const val CREATE_FAILED_TEXT = "Could not create the GitLab Duo Tutorial project. See the Error Log."
private const val OPEN_FAILED_TEXT = "Could not open the GitLab Duo Tutorial. See the Error Log."
private const val TOGGLE_TEXT =
  "Code Suggestions is turned off. Turn it on from the GitLab Duo status menu to try the completion steps."
private const val NO_LICENSE_TEXT =
  "Code Suggestions is unavailable: Valid GitLab license. Open GitLab Duo diagnostics for details."

private const val WAIT_SECONDS = 10L

/**
 * The handler, the opener and a writer over [TutorialWorkspaceFixture], with every UI and platform
 * seam replaced by a recorder: `asyncExec` becomes [uiQueue], `IProgressService.runInUI` becomes
 * [runInUI] (inline by default, wrapping like the real one), the editor open, the notifications,
 * the confirmation dialog, the preference page and both Code Suggestions reads are lambdas over
 * fields. The scheduler seam only records, so a spec chooses when — and on which thread — each
 * writer's body runs.
 */
private class HandlerFixture {
  val ws = TutorialWorkspaceFixture()
  val ownership: DuoTutorialOwnership = spyk(ws.ownership)
  val uiQueue = ArrayDeque<Runnable>()
  val runInUICalls = mutableListOf<Pair<IRunnableWithProgress, ISchedulingRule>>()
  var runInUI: (IRunnableWithProgress, ISchedulingRule) -> Unit = { runnable, _ ->
    try {
      runnable.run(NullProgressMonitor())
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      throw InvocationTargetException(e)
    }
  }
  var ruleHeldAtPost: ISchedulingRule? = null
  val opened = mutableListOf<IFile>()
  var openEditor: (IFile) -> Boolean = {
    opened += it
    true
  }
  val notices = mutableListOf<String>()
  var localEnabled = true
  var engagedChecks: List<String> = emptyList()
  var duoWithoutProject = true
  var dialogAnswer = false
  var dialogCount = 0
  var preferencesOpened = 0
  val scheduled = ArrayDeque<Pair<DuoTutorialWorkspaceWriter, (WriterOutcome) -> Unit>>()
  var schedule: (DuoTutorialWorkspaceWriter, (WriterOutcome) -> Unit) -> Unit = { w, done -> scheduled += w to done }
  var newWriter: () -> DuoTutorialWorkspaceWriter = {
    DuoTutorialWorkspaceWriter(ws.workspace, ownership, { STATE_DIRECTORY }, ws.fileSystem) { "id-1" }
  }
  val window: IWorkbenchWindow = mockk()
  var activeWindow: IWorkbenchWindow? = window
  var fallbackWindow: () -> IWorkbenchWindow? = { null }
  val runInUIWindows = mutableListOf<IWorkbenchWindow>()
  val event: ExecutionEvent = mockk()

  init {
    every { ws.store.getBoolean(PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT) } answers { duoWithoutProject }
  }

  val opener = DuoTutorialEditorOpener(
    onUiThread = {
      ruleHeldAtPost = Job.getJobManager().currentRule()
      uiQueue += it
    },
    fallbackWindow = { fallbackWindow() },
    runInUI = { target, runnable, rule ->
      runInUIWindows += target
      runInUICalls += runnable to rule
      runInUI(runnable, rule)
    },
    ownership = ownership,
    openEditor = { openEditor(it) },
    localCodeSuggestionsEnabled = { localEnabled },
    engagedCheckIds = { engagedChecks },
    notify = { notices += it },
  )

  val handler = DuoTutorialHandler(
    preferences = { ws.store },
    activeWindow = { activeWindow },
    askToOpenPreferences = {
      dialogCount++
      dialogAnswer
    },
    openPreferences = { preferencesOpened++ },
    newWriter = { newWriter() },
    schedule = { w, done -> schedule(w, done) },
    opener = opener,
    notify = { notices += it },
  )

  fun execute() = handler.execute(event)

  /** Runs the oldest scheduled writer's body on this thread and reports its outcome, as `done` would. */
  fun runScheduled(monitor: IProgressMonitor = NullProgressMonitor()) {
    val (writer, done) = scheduled.removeFirst()
    writer.runInWorkspace(monitor)
    done(writer.outcome)
  }

  /** Reports [outcome] for the oldest scheduled writer without running it. */
  fun report(outcome: WriterOutcome) {
    val (_, done) = scheduled.removeFirst()
    done(outcome)
  }

  /** The UI thread's turn: runs what was posted with asyncExec, on this thread. */
  fun drainUi() {
    while (uiQueue.isNotEmpty()) uiQueue.removeFirst().run()
  }
}

/** Polls [condition] for up to [WAIT_SECONDS]; a plain function so the wait is not on a coroutine. */
private fun awaitUntil(condition: () -> Boolean) {
  val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
  while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
}

private fun awaitState(job: Job, state: Int) {
  awaitUntil { job.state == state }
  job.state shouldBe state
}

class DuoTutorialHandlerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("execute") {
    it("reads no workspace state and judges no ownership on the UI thread: it only schedules the writer") {
      val f = HandlerFixture()

      f.execute()

      f.scheduled.size shouldBe 1
      verify(exactly = 0) { f.ownership.isOwned(any()) }
      verify(exactly = 0) { f.ws.project.exists() }
      f.ws.calls.shouldBeEmpty()
      f.notices.shouldBeEmpty()
      f.uiQueue.shouldBeEmpty()
    }

    it("does not schedule when Duo is disabled for non-GitLab projects: asks once, Yes opens the preferences") {
      val f = HandlerFixture()
      f.duoWithoutProject = false
      f.dialogAnswer = true

      f.execute()

      f.scheduled.shouldBeEmpty()
      f.dialogCount shouldBe 1
      f.preferencesOpened shouldBe 1
      f.ws.calls.shouldBeEmpty()
    }

    it("does not schedule when Duo is disabled for non-GitLab projects: No opens nothing") {
      val f = HandlerFixture()
      f.duoWithoutProject = false
      f.dialogAnswer = false

      f.execute()

      f.scheduled.shouldBeEmpty()
      f.dialogCount shouldBe 1
      f.preferencesOpened shouldBe 0
    }
  }

  describe("outcome handling") {
    it("Ready: the editor is opened exactly once, through the UI hop and runInUI") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)

      f.execute()
      f.report(WriterOutcome.Ready(f.ws.file))
      f.opened.shouldBeEmpty() // not yet: the open is on the UI thread's turn
      f.drainUi()

      f.opened shouldContainExactly listOf(f.ws.file)
      f.runInUICalls.size shouldBe 1
      f.notices.shouldBeEmpty()
    }

    it("Refused: the refusal is notified and nothing is opened") {
      val f = HandlerFixture()

      f.execute()
      f.report(WriterOutcome.Refused(RefuseReason.PROJECT_CLOSED))
      f.drainUi()

      f.notices shouldContainExactly listOf(CLOSED_TEXT)
      f.opened.shouldBeEmpty()
      f.runInUICalls.shouldBeEmpty()

      f.execute()
      f.report(WriterOutcome.Refused(RefuseReason.NOT_OWNED))
      f.notices shouldContainExactly listOf(CLOSED_TEXT, NOT_OWNED_TEXT)
    }

    it("Failed: the failure is notified and nothing is opened") {
      val f = HandlerFixture()

      f.execute()
      f.report(WriterOutcome.Failed)
      f.drainUi()

      f.notices shouldContainExactly listOf(CREATE_FAILED_TEXT)
      f.opened.shouldBeEmpty()
    }

    it("Cancelled: nothing at all") {
      val f = HandlerFixture()

      f.execute()
      f.report(WriterOutcome.Cancelled)
      f.drainUi()

      f.notices.shouldBeEmpty()
      f.opened.shouldBeEmpty()
      f.uiQueue.shouldBeEmpty()
    }
  }

  describe("ownership is decided inside the job, then re-verified under the rule") {
    it("a second run while the first is still before the property write waits and then opens (no false refusal)") {
      val f = HandlerFixture()
      // One worker thread models the root rule: writers run one after the other, in order.
      val executor = Executors.newSingleThreadExecutor()
      f.schedule = { writer, done ->
        executor.submit {
          writer.runInWorkspace(NullProgressMonitor())
          done(writer.outcome)
        }
      }
      val firstPaused = CountDownLatch(1)
      val resume = CountDownLatch(1)
      f.ws.onOpen = {
        firstPaused.countDown()
        resume.await(WAIT_SECONDS, TimeUnit.SECONDS)
      }

      f.execute()
      firstPaused.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      f.execute() // the project exists, is open, and has no property yet
      verify(exactly = 0) { f.ownership.isOwned(any()) }
      resume.countDown()
      executor.shutdown()
      executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

      f.drainUi()
      f.notices.shouldBeEmpty()
      f.opened shouldContainExactly listOf(f.ws.file, f.ws.file)
      f.ws.calls.count { it == "create" } shouldBe 1
    }

    it("a same-named user project that appears before the job re-reads is refused and never opened") {
      val f = HandlerFixture()

      f.execute()
      f.ws.userProject(open = true)
      f.runScheduled()
      f.drainUi()

      f.notices shouldContainExactly listOf(NOT_OWNED_TEXT)
      f.opened.shouldBeEmpty()
      f.ws.calls.shouldBeEmpty()
    }

    it("a project replaced after Ready and before the UI turn fails the re-verification: refused, not opened") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)

      f.execute()
      f.runScheduled()
      // Between Ready and the UI turn: deleted and re-created by the user, same name, no ID.
      f.ws.property = null
      f.ws.location = DEFAULT_LOCATION.toUri()
      f.drainUi()

      f.runInUICalls.size shouldBe 1
      f.notices shouldContainExactly listOf(NOT_OWNED_TEXT)
      f.opened.shouldBeEmpty()
    }

    it("a project closed after Ready is refused with the closed wording") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)

      f.execute()
      f.runScheduled()
      f.ws.open = false
      f.drainUi()

      f.notices shouldContainExactly listOf(CLOSED_TEXT)
      f.opened.shouldBeEmpty()
    }

    it("a non-project folder at the default location changes nothing: Ready, and the folder is never touched") {
      val f = HandlerFixture()
      f.ws.fileSystem.existing.add(DEFAULT_LOCATION)

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.opened shouldContainExactly listOf(f.ws.file)
      f.notices.shouldBeEmpty()
      f.ws.fileSystem.touched.none { it.startsWith(DEFAULT_LOCATION) } shouldBe true
    }
  }

  describe("the job manager's part") {
    it("a cancel while queued behind a root-rule job leaves the initial Cancelled outcome: no notice, no editor") {
      val f = HandlerFixture()
      val blockerRunning = CountDownLatch(1)
      val release = CountDownLatch(1)
      val blocker = object : Job("tutorial-test-blocker") {
        override fun run(monitor: IProgressMonitor): IStatus {
          blockerRunning.countDown()
          release.await(WAIT_SECONDS, TimeUnit.SECONDS)
          return Status.OK_STATUS
        }
      }
      blocker.rule = f.ws.root
      blocker.schedule()
      blockerRunning.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      val writer = f.newWriter()
      val finished = CountDownLatch(1)
      writer.addJobChangeListener(object : JobChangeAdapter() {
        override fun done(event: IJobChangeEvent) = finished.countDown()
      })
      f.newWriter = { writer }
      f.schedule = ::scheduleAndReport

      try {
        f.execute()
        awaitState(writer, Job.WAITING)
        writer.cancel() shouldBe true
        finished.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      } finally {
        release.countDown()
        blocker.join()
      }

      writer.outcome shouldBe WriterOutcome.Cancelled
      f.notices.shouldBeEmpty()
      f.uiQueue.shouldBeEmpty()
      f.opened.shouldBeEmpty()
      f.ws.calls.shouldBeEmpty()
    }

    it("opens without a rule on the UI hop, then under runInUI(root) whose monitor keeps the event loop turning") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      // The fake display: what the rule-holding job hands to syncExec lands here, and only the
      // event loop (readAndDispatch) runs it.
      val pending = LinkedBlockingQueue<Runnable>()
      val dispatched = AtomicInteger()
      var uiTouchedByHolder = false
      val holderDone = CountDownLatch(1)
      val holder = object : Job("tutorial-test-holder") {
        override fun run(monitor: IProgressMonitor): IStatus {
          val ran = CountDownLatch(1)
          pending += Runnable {
            uiTouchedByHolder = true
            ran.countDown()
          }
          ran.await(WAIT_SECONDS, TimeUnit.SECONDS) // syncExec: the job waits for the UI
          return Status.OK_STATUS
        }
      }
      holder.rule = f.ws.root
      holder.addJobChangeListener(object : JobChangeAdapter() {
        override fun done(event: IJobChangeEvent) = holderDone.countDown()
      })
      // What IProgressService.runInUI does on the UI thread: beginRule with a monitor that runs
      // the event loop while it waits (ProgressManager.RunnableWithStatus + EventLoopProgressMonitor).
      f.runInUI = { runnable, rule ->
        val manager = Job.getJobManager()
        val eventLoopMonitor = object : NullProgressMonitor() {
          override fun isCanceled(): Boolean {
            pending.poll()?.let {
              dispatched.incrementAndGet()
              it.run()
            }
            return false
          }
        }
        manager.beginRule(rule, eventLoopMonitor)
        try {
          runnable.run(eventLoopMonitor)
        } finally {
          manager.endRule(rule)
        }
      }

      holder.schedule()
      awaitUntil { pending.isNotEmpty() }
      pending.size shouldBe 1 // the holder owns the root rule and is now waiting on the UI

      f.execute()
      f.runScheduled() // Ready (the writer body runs here, outside the platform's rule handling)
      f.uiQueue.size shouldBe 1
      f.ruleHeldAtPost.shouldBeNull() // posted with asyncExec, no rule in hand
      f.runInUICalls.shouldBeEmpty()
      f.drainUi() // this thread is the UI thread: runInUI blocks on the rule but keeps dispatching
      holderDone.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

      dispatched.get() shouldBe 1
      uiTouchedByHolder shouldBe true
      f.runInUICalls.single().second shouldBe f.ws.root
      f.opened shouldContainExactly listOf(f.ws.file)
      f.notices.shouldBeEmpty()
      Job.getJobManager().currentRule().shouldBeNull()
    }
  }

  describe("Code Suggestions state is read when the editor opens (R7)") {
    it("local setting: enabled at command time, turned off before the open: one notice") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = true

      f.execute()
      f.runScheduled()
      f.localEnabled = false
      f.drainUi()

      f.opened.size shouldBe 1
      f.notices shouldContainExactly listOf(TOGGLE_TEXT)
    }

    it("local setting: disabled at command time, turned on before the open: no notice") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = false

      f.execute()
      f.runScheduled()
      f.localEnabled = true
      f.drainUi()

      f.opened.size shouldBe 1
      f.notices.shouldBeEmpty()
    }

    it("feature state: clear at command time, a check engaged before the open: one notice") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.engagedChecks = emptyList()

      f.execute()
      f.runScheduled()
      f.engagedChecks = listOf("code-suggestions-no-license")
      f.drainUi()

      f.notices shouldContainExactly listOf(NO_LICENSE_TEXT)
    }

    it("feature state: engaged at command time, cleared before the open: no notice") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.engagedChecks = listOf("code-suggestions-no-license")

      f.execute()
      f.runScheduled()
      f.engagedChecks = emptyList()
      f.drainUi()

      f.notices.shouldBeEmpty()
    }

    it("wording: an engaged check names the check and points at diagnostics, not at the toggle") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = true
      f.engagedChecks = listOf("code-suggestions-no-license")

      f.execute()
      f.runScheduled()
      f.drainUi()

      val notice = f.notices.single()
      notice shouldBe NO_LICENSE_TEXT
      notice shouldNotContain "status menu"
      notice shouldNotContain "Turn it on"
    }

    it("wording: local setting off wins when both apply") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = false
      f.engagedChecks = listOf("code-suggestions-no-license")

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.notices shouldContainExactly listOf(TOGGLE_TEXT)
    }

    it("only document-scoped checks engaged (the previously active file's) is no reason: no notice (ruling R5)") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = true
      f.engagedChecks = listOf(
        "code-suggestions-document-unsupported-language",
        "code-suggestions-document-disabled-language",
        "code-suggestions-file-excluded",
      )

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.opened.size shouldBe 1
      f.notices.shouldBeEmpty()
    }

    it("a document-scoped check ahead of an environment check: the environment check is named") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = true
      f.engagedChecks = listOf("code-suggestions-document-unsupported-language", "code-suggestions-no-license")

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.notices shouldContainExactly listOf(NO_LICENSE_TEXT)
    }

    it("local setting off still wins over a document-scoped check") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.localEnabled = false
      f.engagedChecks = listOf("code-suggestions-file-excluded")

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.notices shouldContainExactly listOf(TOGGLE_TEXT)
    }

    it("no notice at all after a refusal (the editor never opened)") {
      val f = HandlerFixture()
      f.ws.userProject(open = true)
      f.localEnabled = false

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.notices shouldContainExactly listOf(NOT_OWNED_TEXT)
    }
  }

  describe("runInUI failures") {
    it("InterruptedException (the rule wait was cancelled) is a silent cancel that leaks nothing into the UI turn") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.runInUI = { _, _ -> throw InterruptedException("cancelled by the user") }

      f.execute()
      f.runScheduled()
      shouldNotThrowAny { f.drainUi() }

      f.notices.shouldBeEmpty()
      f.opened.shouldBeEmpty()
    }

    it("InvocationTargetException (re-verification or open threw) notifies the failure and logs only the class name") {
      val logged = captureLog()
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.openEditor = { throw PartInitException("no editor for /ws/GitLab Duo Tutorial/duo_tutorial.js") }

      f.execute()
      f.runScheduled()
      shouldNotThrowAny { f.drainUi() }

      f.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
      logged.any { it.contains(PartInitException::class.java.name) } shouldBe true
      logged.forEach {
        it shouldNotContain "duo_tutorial.js"
        it shouldNotContain "/ws/"
      }
    }

    it("no active page (open returned false) is reported as a failure too") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.openEditor = { false }

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
    }
  }

  describe("workbench window resolution") {
    it("the event's window is used when it has one; the fallback is never consulted") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      var fallbackReads = 0
      f.fallbackWindow = {
        fallbackReads++
        mockk()
      }

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.runInUIWindows shouldContainExactly listOf(f.window)
      fallbackReads shouldBe 0
      f.opened.size shouldBe 1
    }

    it("no window on the event: the workbench's active window is used and the tutorial opens") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.activeWindow = null
      val fallback: IWorkbenchWindow = mockk()
      f.fallbackWindow = { fallback }

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.runInUIWindows shouldContainExactly listOf(fallback)
      f.opened.size shouldBe 1
      f.notices.shouldBeEmpty()
    }

    it("no window anywhere: a failure notice, nothing opened") {
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.activeWindow = null
      f.fallbackWindow = { null }

      f.execute()
      f.runScheduled()
      f.drainUi()

      f.runInUIWindows.shouldBeEmpty()
      f.opened.shouldBeEmpty()
      f.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
    }

    it("the fallback read throwing (workbench closed) is contained: a failure notice, class name only") {
      val logged = captureLog()
      val f = HandlerFixture()
      f.ws.ownedProject("id-1", fileExists = true)
      f.activeWindow = null
      f.fallbackWindow = { throw IllegalStateException("Workbench has not been created yet.") }

      f.execute()
      f.runScheduled()
      shouldNotThrowAny { f.drainUi() }

      f.opened.shouldBeEmpty()
      f.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
      logged.any { it.contains(IllegalStateException::class.java.name) } shouldBe true
      logged.forEach { it shouldNotContain "Workbench has not been created yet." }
    }
  }

  describe("messages") {
    it("the preference dialog names the preference page label exactly") {
      DuoTutorialMessages.PREFERENCES_QUESTION shouldContain
        "\"Enable Duo features when no GitLab project is detected\""
      DuoTutorialMessages.DIALOG_TITLE shouldBe "GitLab Duo Tutorial"
    }

    it("codeSuggestionsNotice ignores exactly the four document-scoped check ids") {
      DuoTutorialMessages.DOCUMENT_SCOPED_CHECK_IDS shouldBe setOf(
        "code-suggestions-document-unsupported-language",
        "code-suggestions-document-disabled-language",
        "code-suggestions-file-excluded",
        "duo-disabled-for-project",
      )
      DuoTutorialMessages.codeSuggestionsNotice(true, emptyList()).shouldBeNull()
      DuoTutorialMessages.codeSuggestionsNotice(true, listOf("code-suggestions-file-excluded")).shouldBeNull()
      // Evaluated per document in the language server: at open time it describes the previous file's project.
      DuoTutorialMessages.codeSuggestionsNotice(true, listOf("duo-disabled-for-project")).shouldBeNull()
      DuoTutorialMessages.codeSuggestionsNotice(
        true,
        listOf("code-suggestions-document-disabled-language", "code-suggestions-no-license"),
      ) shouldBe NO_LICENSE_TEXT
      DuoTutorialMessages.codeSuggestionsNotice(false, listOf("code-suggestions-no-license")) shouldBe TOGGLE_TEXT
      // An id the label table does not know is still named, as diagnostics does.
      DuoTutorialMessages.codeSuggestionsNotice(true, listOf("brand-new-check")) shouldBe
        "Code Suggestions is unavailable: brand-new-check. Open GitLab Duo diagnostics for details."
    }
  }
})
