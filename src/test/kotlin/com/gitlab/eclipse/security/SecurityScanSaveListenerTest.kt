package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.ITextEditor

/** Recorded `launch` calls, so a test can assert both "how many" and "with what". */
private typealias Launches = MutableList<Pair<String?, SecurityScanSource>>

/**
 * Builds a listener with every workbench touching collaborator replaced by a lambda.
 *
 * The point of the split (design constraint: SWT cannot run in this container) is that the whole
 * save decision is reachable headless: elements are plain strings, the key is the string itself and
 * the URI is derived from it, so the only thing under test is the order and presence of the gates.
 */
private fun listener(
  launches: Launches,
  onSave: Boolean = true,
  active: Any? = null,
  guard: RevertGuard = RevertGuard(),
  uriOf: (Any) -> String? = { "file:/w/$it" },
) = SecurityScanSaveListener(
  guard = guard,
  scanOnSaveEnabled = { onSave },
  keyOf = { it.toString() },
  uriOf = uriOf,
  isActiveEditorInput = { it == active },
  launch = { uri, source ->
    launches += uri to source
    SecurityScanLaunchOutcome.SENT
  },
)

/**
 * Workbench mocks for the install/uninstall surface: an active window whose page already exists at
 * install time (the normal start path), plus a second window that "opens" later by firing the
 * [IWindowListener] that install registered — the multi-monitor `Window > New Window` case.
 *
 * Requires `mockkStatic(PlatformUI::class)` to be active before construction.
 */
private class WorkbenchHarness {
  val workbench = mockk<IWorkbench>(relaxed = true)
  val installPage = mockk<IWorkbenchPage>(relaxed = true)
  val latePage = mockk<IWorkbenchPage>(relaxed = true)
  private val lateWindow = mockk<IWorkbenchWindow>()

  init {
    every { PlatformUI.getWorkbench() } returns workbench
    val installWindow = mockk<IWorkbenchWindow>()
    every { workbench.activeWorkbenchWindow } returns installWindow
    every { installWindow.activePage } returns installPage
    every { installPage.editorReferences } returns emptyArray()
    // `activePage` is non-null by the time `windowOpened` is delivered: verified from bytecode,
    // see the ordering note on SecurityScanSaveListener.windowListener.
    every { lateWindow.activePage } returns latePage
    every { latePage.editorReferences } returns emptyArray()
  }

  /** The [IWindowListener] that install() registered; fails the test if none was. */
  fun registeredWindowListener(): IWindowListener {
    val captured = slot<IWindowListener>()
    verify { workbench.addWindowListener(capture(captured)) }
    return captured.captured
  }

  /** Simulates the platform opening a new workbench window after install. */
  fun openLateWindow() = registeredWindowListener().windowOpened(lateWindow)

  /** Puts one text editor, backed by the returned provider, into the late window's page. */
  fun editorInLateWindow(): IDocumentProvider {
    val provider = mockk<IDocumentProvider>(relaxed = true)
    val editor = mockk<ITextEditor>()
    every { editor.getAdapter(ITextEditor::class.java) } returns editor
    every { editor.documentProvider } returns provider
    val reference = mockk<IEditorReference>()
    every { reference.getPart(false) } returns editor
    every { latePage.editorReferences } returns arrayOf(reference)
    return provider
  }
}

class SecurityScanSaveListenerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeEach {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
  }

  afterEach {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
  }

  describe("RevertGuard") {
    // The platform's real order. Verified from bytecode, not from memory:
    // org.eclipse.core.filebuffers-3.8.500 ResourceFileBuffer.revert -> handleFileContentChanged,
    // and ResourceTextFileBuffer.handleFileContentChanged fires, unconditionally and in this order,
    // fireBufferContentAboutToBeReplaced (@90), fireBufferContentReplaced (@182),
    // fireDirtyStateChanged (@257). org.eclipse.ui.editors-3.20.200
    // TextFileDocumentProvider$FileBufferListener forwards all three synchronously without
    // reordering. So `contentReplaced` arrives BEFORE the dirty edge that has to be suppressed.
    it("suppresses the dirty edge of a revert delivered in the platform's real order") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("suppresses the dirty edge even when contentReplaced arrives after it") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
      // The record was consumed by the edge above; a late `contentReplaced` must not resurrect it
      // and swallow the user's next real save.
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe true
    }

    it("allows a normal save") {
      RevertGuard().shouldScanOnClean("editorA") shouldBe true
    }

    it("allows the save that follows a completed revert") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
      guard.shouldScanOnClean("editorA") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }

    it("re-stamps the record on contentReplaced so a slow revert is not expired mid-flight") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      now = REVERT_WINDOW_MS - 1
      guard.contentReplaced("editorA")
      // Past the window measured from `aboutToBeReplaced`, but only 2ms past the re-stamp.
      now = REVERT_WINDOW_MS + 1
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("discards a record outright") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.discard("editorA")
      guard.entryCountForTest() shouldBe 0
      guard.shouldScanOnClean("editorA") shouldBe true
    }

    it("keeps a revert on one element from suppressing a save on another") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.shouldScanOnClean("editorB") shouldBe true
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("still suppresses the dirty edge inside the window when contentReplaced never arrives") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      guard.entryCountForTest() shouldBe 1
      now = REVERT_WINDOW_MS - 1
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("does not leak entries when neither contentReplaced nor a dirty edge ever arrives") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      guard.entryCountForTest() shouldBe 1

      // Past the window with nothing to consume the record: the next decision drops it, and is
      // itself the first one to see a clean slate again.
      now = REVERT_WINDOW_MS + 1
      guard.shouldScanOnClean("editorA") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }

    it("drops a stale entry even when the next decision is about a different element") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      now = REVERT_WINDOW_MS + 1
      guard.shouldScanOnClean("editorB") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }
  }

  describe("SecurityScanSaveListener") {
    it("launches a SAVE scan for the active editor's own save") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a").elementDirtyStateChanged("a", false)
      launches shouldBe listOf("file:/w/a" to SecurityScanSource.SAVE)
    }

    it("does not launch anything when scanFileOnSave is off") {
      val launches: Launches = mutableListOf()
      listener(launches, onSave = false, active = "a").elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    // The order the platform really delivers (see the RevertGuard block above): the replacement is
    // announced, then completed, and only then does the buffer go clean. This is the test that
    // stands between `File > Revert` and an upload the user never asked for.
    it("does not launch a scan for a revert delivered in the platform's real order") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementContentReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    it("does not launch a scan when the dirty edge precedes contentReplaced") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      subject.elementContentReplaced("a")
      launches.size shouldBe 0
    }

    it("launches again on a real save once the revert has completed") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementContentReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      subject.elementDirtyStateChanged("a", false)
      launches shouldBe listOf("file:/w/a" to SecurityScanSource.SAVE)
    }

    it("launches at most once for a Save All across several dirty editors") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "b")
      listOf("a", "b", "c").forEach { subject.elementDirtyStateChanged(it, false) }
      launches shouldBe listOf("file:/w/b" to SecurityScanSource.SAVE)
    }

    it("ignores the transition that makes an element dirty") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a").elementDirtyStateChanged("a", true)
      launches.size shouldBe 0
    }

    it("ignores an element that has no file URI") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a", uriOf = { null }).elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    it("ignores a null element") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced(null)
      subject.elementDirtyStateChanged(null, false)
      subject.elementContentReplaced(null)
      launches.size shouldBe 0
    }

    it("does not treat a deleted element as a save, and drops the record it left behind") {
      val launches: Launches = mutableListOf()
      val guard = RevertGuard()
      val subject = listener(launches, active = "a", guard = guard)
      subject.elementContentAboutToBeReplaced("a")
      subject.elementDeleted("a")
      launches.size shouldBe 0
      // Nothing can consume the record now that the element is gone, so it has to go here.
      guard.entryCountForTest() shouldBe 0
    }

    it("survives a collaborator that throws instead of aborting the platform's listener loop") {
      // TextFileDocumentProvider$FileBufferListener.dirtyStateChanged iterates its listeners with
      // no exception table, so one throw from us skips every remaining listener — including the
      // editor's own dirty handling, which would leave a stale `*` on the user's tab.
      val subject = SecurityScanSaveListener(
        scanOnSaveEnabled = { error("preference store is gone") },
        keyOf = { error("editor input is gone") },
        uriOf = { "file:/w/$it" },
        isActiveEditorInput = { true },
        launch = { _, _ -> SecurityScanLaunchOutcome.SENT },
      )
      subject.elementDirtyStateChanged("a", false)
      subject.elementContentAboutToBeReplaced("a")
      subject.elementContentReplaced("a")
      subject.elementDeleted("a")
      subject.elementMoved("a", "b")
    }

    it("drops the revert record of an element a move left behind") {
      val launches: Launches = mutableListOf()
      val guard = RevertGuard()
      val subject = listener(launches, active = "a", guard = guard)
      subject.elementContentAboutToBeReplaced("a")
      // Nothing will ever send `elementContentReplaced` for the old identity now, so the record
      // has to go here rather than wait out the window.
      subject.elementMoved("a", "b")
      guard.entryCountForTest() shouldBe 0
    }
  }

  describe("SecurityScanSaveListener window lifecycle") {
    beforeEach { mockkStatic(PlatformUI::class) }
    afterEach { unmockkStatic(PlatformUI::class) }

    // Why every one of these opens a window AFTER install: on the normal start path a page already
    // exists, and an install that only attaches to that page leaves `Window > New Window` (common
    // on multi-monitor setups) unobserved — the first editor of a type opened there would save
    // without a scan. The window listener has to be registered even when install found a page.

    it("registers the window listener even when a page already exists at install time") {
      val harness = WorkbenchHarness()
      SecurityScanSaveListener().install()
      verify(exactly = 1) { harness.workbench.addWindowListener(any()) }
    }

    // Passes before and after the fix on purpose: it pins that the fix KEPT today's behaviour for
    // the window that already exists, it does not pin the fix itself.
    it("still attaches to the page that is already open at install time") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      verify(exactly = 1) { harness.installPage.addPartListener(subject) }
    }

    it("adds a part listener to the page of a window opened after install") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
    }

    it("attaches to the provider of an editor already open in a window opened after install") {
      val harness = WorkbenchHarness()
      val provider = harness.editorInLateWindow()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { provider.addElementStateListener(subject) }
    }

    it("uninstall removes the window listener that install registered") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      val windowListener = harness.registeredWindowListener()
      subject.uninstall()
      verify(exactly = 1) { harness.workbench.removeWindowListener(windowListener) }
    }

    it("uninstall removes the part listener added for a window opened after install") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      subject.uninstall()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    it("uninstall detaches the provider attached for a window opened after install") {
      val harness = WorkbenchHarness()
      val provider = harness.editorInLateWindow()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      subject.uninstall()
      verify(exactly = 1) { provider.removeElementStateListener(subject) }
    }
  }
})
