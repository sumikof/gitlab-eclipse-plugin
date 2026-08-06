package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
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
  val backgroundPage = mockk<IWorkbenchPage>(relaxed = true)
  val lateWindow = mockk<IWorkbenchWindow>()
  private val installWindow = mockk<IWorkbenchWindow>()
  private val backgroundWindow = mockk<IWorkbenchWindow>()
  private val throwingWindow = mockk<IWorkbenchWindow>()

  init {
    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.activeWorkbenchWindow } returns installWindow
    every { workbench.workbenchWindows } returns arrayOf(installWindow)
    every { installWindow.activePage } returns installPage
    every { installPage.editorReferences } returns emptyArray()
    every { installPage.workbenchWindow } returns installWindow
    // `activePage` is non-null by the time `windowOpened` is delivered: verified from bytecode,
    // see the ordering note on SecurityScanSaveListener.windowListener.
    every { lateWindow.activePage } returns latePage
    every { latePage.editorReferences } returns emptyArray()
    every { latePage.workbenchWindow } returns lateWindow
    every { backgroundWindow.activePage } returns backgroundPage
    every { backgroundPage.editorReferences } returns emptyArray()
    every { backgroundPage.workbenchWindow } returns backgroundWindow
    every { throwingWindow.activePage } throws RuntimeException("adapter factory failed")
  }

  /**
   * Puts a second, NON-active window into the set of windows already open at install time. The
   * window listener structurally cannot reach it: `Workbench.createWorkbenchWindow` returns at @288
   * without `fireWindowOpened` (@280) when the context already holds an `IWorkbenchWindow` — which
   * is exactly the set `getWorkbenchWindows()` enumerates. The restored-workspace case.
   */
  fun backgroundWindowAtInstall() {
    every { workbench.workbenchWindows } returns arrayOf(installWindow, backgroundWindow)
  }

  /**
   * Puts a window whose page walk throws AHEAD of a healthy background window in the set already
   * open at install time. `install`'s enumeration reaches `getAdapter` — third-party code — once
   * per window, so any one window's walk can throw; throwing from `activePage` is the cheapest
   * stand-in for a failure anywhere inside that window's walk. The ordering matters: only a bad
   * window that comes FIRST can prove the walk survives it to reach the one behind it.
   */
  fun throwingWindowAheadOfBackgroundWindowAtInstall() {
    every { workbench.workbenchWindows } returns arrayOf(throwingWindow, backgroundWindow)
  }

  /** The [IWindowListener] that install() registered; fails the test if none was. */
  fun registeredWindowListener(): IWindowListener {
    val captured = slot<IWindowListener>()
    verify { workbench.addWindowListener(capture(captured)) }
    return captured.captured
  }

  /** Simulates the platform opening a new workbench window after install. */
  fun openLateWindow() = registeredWindowListener().windowOpened(lateWindow)

  /** Simulates the platform closing the late window. */
  fun closeLateWindow() = registeredWindowListener().windowClosed(lateWindow)

  /**
   * Leaves the late window and its page the way a delayed `windowClosed` really finds them:
   * `WorkbenchPage.close(ZZ)` nulled `legacyWindow` (@613) before `hardClose` nulled `page` (@350),
   * so by callback time BOTH references are gone (verified from bytecode, 3.133.0 and 3.137.0).
   */
  fun tearDownLatePageBeforeCallback() {
    every { lateWindow.activePage } returns null
    every { latePage.workbenchWindow } returns null
  }

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

  /** A free-standing editor reference for delivering part events directly. */
  fun editorReference(): Pair<IEditorReference, IDocumentProvider> {
    val provider = mockk<IDocumentProvider>(relaxed = true)
    val editor = mockk<ITextEditor>()
    every { editor.getAdapter(ITextEditor::class.java) } returns editor
    every { editor.documentProvider } returns provider
    val reference = mockk<IEditorReference>()
    every { reference.getPart(false) } returns editor
    return reference to provider
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
    // the window that already exists, it does not pin the fix itself. Since the harness now puts
    // the active window into `workbenchWindows` too, `exactly = 1` additionally pins that the
    // enumeration and the active-window path compose without double-registering the part listener.
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

    // Eclipse restores several workbench windows after a restart; only one of them is active. The
    // window listener structurally cannot reach the others (see backgroundWindowAtInstall), so
    // install() itself has to enumerate them or their editors save without a scan.
    it("attaches to the page of a window that is already open but not active at install time") {
      val harness = WorkbenchHarness()
      harness.backgroundWindowAtInstall()
      val subject = SecurityScanSaveListener()
      subject.install()
      verify(exactly = 1) { harness.backgroundPage.addPartListener(subject) }
    }

    // The window enumeration runs third-party code (`getAdapter`) once per already-open window,
    // so one broken background window is a reachable failure. The walk must survive it and still
    // attach the windows behind it in the enumeration.
    it("a background window whose walk throws does not stop install from attaching the next window") {
      val harness = WorkbenchHarness()
      harness.throwingWindowAheadOfBackgroundWindowAtInstall()
      val subject = SecurityScanSaveListener()
      subject.install()
      verify(exactly = 1) { harness.backgroundPage.addPartListener(subject) }
    }

    // The active window is the one the user is looking at; attaching to it must not depend on the
    // enumeration of the OTHER windows surviving.
    it("a background window whose walk throws does not stop install from attaching the active window") {
      val harness = WorkbenchHarness()
      harness.throwingWindowAheadOfBackgroundWindowAtInstall()
      val subject = SecurityScanSaveListener()
      subject.install()
      verify(exactly = 1) { harness.installPage.addPartListener(subject) }
    }

    // The attached-but-inert state: before containment, a throwing background window aborted
    // install AFTER pages were attached, so `installed` stayed false and the `installed` guard in
    // the callbacks made everything already attached permanently inert — with no retry, because
    // install() has exactly one call site. `installed` must still be reached.
    it("a background window whose walk throws does not leave the session inert for later windows") {
      val harness = WorkbenchHarness()
      harness.throwingWindowAheadOfBackgroundWindowAtInstall()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
    }

    // `fireWindowOpened` iterates a listener snapshot taken before our removal can be seen, so a
    // delivery can still arrive after uninstall() finished on the bundle-stop thread. Re-attaching
    // then would leak the listener into providers for the rest of the session.
    it("a windowOpened delivered after uninstall does not re-attach") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.uninstall()
      harness.openLateWindow()
      verify(exactly = 0) { harness.latePage.addPartListener(any<IPartListener2>()) }
    }

    // Keep-behaviour guard: passes before and after this wave by design — the `contained` wrapper
    // on the window callbacks shipped in 5ce697a with no coverage; this pins it. The workbench
    // delivers these through SafeRunner, whose handler logs the FULL exception, and the walk
    // reaches third-party adapter factories whose message can quote a file path — so nothing may
    // propagate. (No windowClosed twin: after this wave every fallible call inside onWindowClosed
    // is individually runCaught, so no injection can reach its `contained` even under mutation —
    // a twin would be green with the wrapper deleted, i.e. non-discriminating.)
    it("a windowOpened whose page walk throws does not propagate out of the callback") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      val badWindow = mockk<IWorkbenchWindow>()
      every { badWindow.activePage } throws RuntimeException("/home/user/secret/path.txt")
      shouldNotThrowAny { harness.registeredWindowListener().windowOpened(badWindow) }
    }

    // Same snapshot race as above, on the part-listener side: a partOpened taken from a stale
    // ListenerList snapshot can be delivered after uninstall() already walked `providers`.
    it("a partOpened delivered after uninstall does not attach the provider") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.uninstall()
      val (reference, provider) = harness.editorReference()
      subject.partOpened(reference)
      verify(exactly = 0) { provider.addElementStateListener(any()) }
    }

    // T2: the normal close path, where the model REMOVE (hardClose @266) fires the callback while
    // the page is still reachable. Keep-behaviour guard: passes before and after this wave (and
    // the previous one) by design — the pre-fix close path also detached this page, via
    // `window.activePage`, which the harness stubs; the discriminating close-path test is the
    // unreachable-page one below.
    it("windowClosed detaches the part listener of the window that closed") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      harness.closeLateWindow()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    it("windowClosed detaches the part listener of a page the closing window can no longer reach") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      harness.tearDownLatePageBeforeCallback()
      harness.closeLateWindow()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    // A throw from the page -> window lookup is NOT evidence of disposal. Collapsing it into the
    // null (= disposed) verdict would silently detach a live page and kill the feature for it;
    // keeping the page costs nothing, because uninstall walks `pages` regardless — which is what
    // the second half asserts, and what closes the blind spot of an `exactly = 0` sitting behind
    // `contained` (a callback that THREW would also satisfy it).
    it("windowClosed keeps a page whose window lookup throws") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      every { harness.latePage.workbenchWindow } throws RuntimeException("model access failed")
      harness.registeredWindowListener().windowClosed(mockk<IWorkbenchWindow>())
      verify(exactly = 0) { harness.latePage.removePartListener(any<IPartListener2>()) }
      subject.uninstall()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    it("windowClosed does not touch a page it never attached to") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      val foreignWindow = mockk<IWorkbenchWindow>()
      val foreignPage = mockk<IWorkbenchPage>(relaxed = true)
      every { foreignWindow.activePage } returns foreignPage
      every { foreignPage.workbenchWindow } returns foreignWindow
      harness.registeredWindowListener().windowClosed(foreignWindow)
      verify(exactly = 0) { foreignPage.removePartListener(any<IPartListener2>()) }
    }

    // Keep-behaviour guard: passes before and after this wave by design. It pins the bookkeeping
    // that windowClosed takes the page out of `pages`, so uninstall cannot detach it a second time.
    it("windowClosed removes the page so uninstall does not detach it a second time") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      harness.closeLateWindow()
      subject.uninstall()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    // T1: the catch rollback. Keep-behaviour guard: passes before and after this wave by design —
    // the rollback shipped in 22915b7 with no coverage; this pins it. The failure is injected at
    // `getWorkbenchWindows` itself — a workbench-level failure — because this wave contained the
    // per-window walk: a throw from inside one window's walk no longer aborts install, so the old
    // `installPage.editorReferences` injection could not keep pinning the rollback.
    it("a failed install takes the window listener back off") {
      val harness = WorkbenchHarness()
      every { harness.workbench.workbenchWindows } throws RuntimeException("workbench going down")
      SecurityScanSaveListener().install()
      verify(exactly = 1) { harness.workbench.removeWindowListener(any()) }
    }

    // T1: the flag placement. Keep-behaviour guard: passes before and after this wave by design —
    // `installed` must stay false after a failed install so a later call really retries. Same
    // injection relocation as the rollback test above.
    it("a failed install leaves the trigger retryable") {
      val harness = WorkbenchHarness()
      every { harness.workbench.workbenchWindows } throws RuntimeException("workbench going down")
      val subject = SecurityScanSaveListener()
      subject.install()
      every { harness.workbench.workbenchWindows } returns emptyArray()
      subject.install()
      verify(exactly = 2) { harness.workbench.addWindowListener(any()) }
    }

    // Keep-behaviour guard: passes before and after this wave by design. One page that is already
    // gone must not keep the providers attached — the KDoc calls that leak non-optional.
    it("uninstall detaches the provider even when a page detach throws") {
      val harness = WorkbenchHarness()
      val provider = harness.editorInLateWindow()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      every { harness.latePage.removePartListener(any<IPartListener2>()) } throws RuntimeException("page is gone")
      subject.uninstall()
      verify(exactly = 1) { provider.removeElementStateListener(subject) }
    }
  }
})
