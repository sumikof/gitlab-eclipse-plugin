package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPart
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.ITextEditor
import org.osgi.framework.Bundle

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

  /**
   * Puts an editor whose `getAdapter` throws [exception] into the page install attaches FIRST
   * (the active window's). `getAdapter` runs third-party adapter-factory code; since this wave
   * that walk is contained like every enumerated window's, so the throw exercises the
   * active-window containment — it can no longer reach install's catch.
   */
  fun brokenAdapterEditorAtInstall(exception: Exception) {
    val part = mockk<IWorkbenchPart>()
    every { part.getAdapter(ITextEditor::class.java) } throws exception
    val reference = mockk<IEditorReference>()
    every { reference.getPart(false) } returns part
    every { installPage.editorReferences } returns arrayOf(reference)
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

@Suppress("LargeClass")
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
    // org.eclipse.core.filebuffers-3.8.500 ResourceFileBuffer.revert ->
    // handleFileContentChanged(true, false), and ResourceTextFileBuffer.handleFileContentChanged
    // fires — only when the new content differs from the document (`replaceContent`, guards @80
    // and @172) — fireBufferContentAboutToBeReplaced (@90) then fireBufferContentReplaced (@182),
    // and unconditionally fireDirtyStateChanged (@257), in that order.
    // org.eclipse.ui.editors-3.20.200 TextFileDocumentProvider$FileBufferListener forwards all
    // three synchronously without reordering. So when the replacement callbacks arrive at all,
    // `contentReplaced` arrives BEFORE the dirty edge that has to be suppressed. (A revert whose
    // buffer already equals disk delivers ONLY the dirty edge and is scanned as if it were a save
    // — the accepted limitation documented on RevertGuard.)
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
    // enumeration of the OTHER windows surviving. Keep-behaviour guard: passes at 12b4bbb too, by
    // accident of the harness rather than by design — the helper only restubs `workbenchWindows`,
    // which the pre-enumeration install() never read, so the old path satisfied the `exactly = 1`
    // vacuously. Against today's code it is a real guard: the harness leaves the install window
    // OUT of the throwing enumeration, so this pins that the dedicated active-window walk exists
    // and stands outside the enumeration — delete that line and the active window is attached by
    // nothing.
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

    // This wave: the ACTIVE window's walk was the one third-party call in install() that was not
    // contained. A throw there used to land in install's own catch — window listener rolled back,
    // `installed` never set, one call site, no retry: the feature died for the session, and it
    // died on the very window whose editors are guaranteed materialised, i.e. the one where
    // `getPart(false)` most reliably returns a part for `getAdapter` to break on.
    it("an active window whose walk throws does not stop install from attaching a background window") {
      val harness = WorkbenchHarness()
      harness.brokenAdapterEditorAtInstall(RuntimeException("adapter factory failed"))
      harness.backgroundWindowAtInstall()
      val subject = SecurityScanSaveListener()
      subject.install()
      verify(exactly = 1) { harness.backgroundPage.addPartListener(subject) }
    }

    // The positive half: install must still reach `installed = true` on the contained path.
    // `installed` is not directly observable, so it is observed through the one gate that reads
    // it here — a window opened later attaches only when install really ended installed.
    it("an active window whose walk throws still leaves install able to attach later windows") {
      val harness = WorkbenchHarness()
      harness.brokenAdapterEditorAtInstall(RuntimeException("adapter factory failed"))
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
    }

    // Containment moved the path-quoting active-walk failure OUT of install's catch (where the
    // two secrecy tests below pin the rule) and INTO `contained` — whose own log line no test
    // pinned. This closes that gap: the property the pre-wave secrecy test carried for this walk
    // must survive the walk's relocation. Keep-behaviour guard: passes before and after this wave
    // by design — pre-wave the same injection reached install's class-name-only catch — and it
    // fails when `contained`'s log line is made to include `e.message` or the exception object.
    it("a file path quoted by a contained walk failure does not reach the log") {
      val harness = WorkbenchHarness()
      harness.brokenAdapterEditorAtInstall(RuntimeException("/home/user/secret/path.txt"))
      val ilog = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      val throwables = mutableListOf<Throwable>()
      every { ilog.warn(capture(messages)) } just Runs
      every { ilog.warn(capture(messages), capture(throwables)) } just Runs
      every { Platform.getLog(any<Bundle>()) } returns ilog
      SecurityScanSaveListener().install()
      val reachedTheLog = messages + throwables.map { it.message.orEmpty() }
      reachedTheLog.none { "/home/user/secret/path.txt" in it } shouldBe true
    }

    // `fireWindowOpened` iterates a listener snapshot taken before our removal can be seen, so a
    // delivery can still arrive after uninstall() finished on the bundle-stop thread. Re-attaching
    // then would leak the listener into providers for the rest of the session.
    it("a windowOpened delivered after uninstall does not re-attach") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      // Captured once: the listener is the same instance across install cycles, and the harness's
      // slot capture cannot be replayed once re-install registers it a second time.
      val windowListener = harness.registeredWindowListener()
      subject.uninstall()
      windowListener.windowOpened(harness.lateWindow)
      verify(exactly = 0) { harness.latePage.addPartListener(any<IPartListener2>()) }
      // The `exactly = 0` sits behind `contained`, which swallows Throwable — a callback that
      // THREW would satisfy it too. Same closing tail as the windowClosed lookup test: re-install
      // and prove the identical delivery does attach, so the suppression above was the
      // `installed` gate and not a dead callback.
      subject.install()
      windowListener.windowOpened(harness.lateWindow)
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
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

    // E (this wave): every window-or-page-scoped attachment that failed once — the enumeration
    // getter, one enumerated window's walk, windowOpened's activePage, a page's addPartListener —
    // had no per-activation retry vehicle: install() has one call site and windowOpened fires only
    // at window creation. windowActivated is that vehicle, and it is the only callback that can
    // ever reach a restored background window whose install-time walk failed. A window nothing
    // else reached stands in for the whole class.
    it("windowActivated attaches the page of a window nothing else reached") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.registeredWindowListener().windowActivated(harness.lateWindow)
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
    }

    // The steady-state cost gate on the vehicle above: activating a window whose page is already
    // attached must stay O(1) — it must NOT re-walk the page's editors, because that walk runs
    // `getPart(false)` and then `getAdapter` (third-party code) under the monitor on the UI
    // thread, once per open editor, on every window switch. A page in `pages` has its part
    // listener on, and partActivated is already the per-editor retry vehicle there. Keep-behaviour
    // label: green before this wave too (vacuously — windowActivated ran nothing at all); what it
    // discriminates is the naive wiring that routes every activation into listenTo.
    it("windowActivated does not walk the editors of a page that is already attached") {
      val harness = WorkbenchHarness()
      val (reference, _) = harness.editorReference()
      every { harness.latePage.editorReferences } returns arrayOf(reference)
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      clearMocks(reference, answers = false)
      harness.registeredWindowListener().windowActivated(harness.lateWindow)
      verify(exactly = 0) { reference.getPart(false) }
    }

    // Same stale-snapshot race as windowOpened: `fireWindowActivated` iterates a listener snapshot
    // taken before our removal can be seen, so an activation can arrive after uninstall() finished
    // on the bundle-stop thread. Same closing tail as the windowOpened twin: `exactly = 0` sits
    // behind `contained`, which a throwing callback would also satisfy, so re-install and prove
    // the identical delivery attaches.
    it("a windowActivated delivered after uninstall does not attach") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      val windowListener = harness.registeredWindowListener()
      subject.uninstall()
      windowListener.windowActivated(harness.lateWindow)
      verify(exactly = 0) { harness.latePage.addPartListener(any<IPartListener2>()) }
      subject.install()
      windowListener.windowActivated(harness.lateWindow)
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
    }

    // The containment twin of the windowOpened test above, for the new callback: the workbench
    // delivers windowActivated through SafeRunner too, whose handler logs the FULL exception, and
    // the activation path reaches `activePage` and (for an unattached page) third-party adapter
    // factories whose message can quote a file path — so nothing may propagate. Keep-behaviour
    // label: green before this wave too (vacuously — the callback ran nothing at all); it fails
    // when windowActivated is wired into the attach path without `contained`.
    it("a windowActivated whose page lookup throws does not propagate out of the callback") {
      val harness = WorkbenchHarness()
      val subject = SecurityScanSaveListener()
      subject.install()
      val badWindow = mockk<IWorkbenchWindow>()
      every { badWindow.activePage } throws RuntimeException("/home/user/secret/path.txt")
      shouldNotThrowAny { harness.registeredWindowListener().windowActivated(badWindow) }
    }

    // The fourth instance of the shape E closes: a page whose addPartListener threw has no part
    // listener, so partActivated can never fire for it, and the platform sends windowOpened only
    // at window creation — before this wave nothing retried that page again. Focusing the window
    // is now the retry: the page is not in `pages` (the failed registration rolled its record
    // back), so the activation runs listenTo again.
    it("windowActivated retries the page registration that failed at windowOpened") {
      val harness = WorkbenchHarness()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } just Runs
      harness.registeredWindowListener().windowActivated(harness.lateWindow)
      verify(exactly = 2) { harness.latePage.addPartListener(subject) }
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
      // Same blind spot and same closing tail as the windowOpened test above: `exactly = 0` is
      // also satisfied by a throwing callback, so re-install and prove the identical delivery
      // attaches.
      subject.install()
      subject.partOpened(reference)
      verify(exactly = 1) { provider.addElementStateListener(subject) }
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

    // D (this wave): `undetachedPages` is teardown bookkeeping and window-close is a teardown
    // event it was not wired into. Left out, a page whose registration AND immediate detach both
    // threw kept the disposed window — and the editors it holds — strongly referenced until
    // uninstall. The record leaving at close is observed through uninstall: once windowClosed
    // dropped it, uninstall has nothing left to detach on that page.
    it("windowClosed drops an undetached page so uninstall makes no further detach on it") {
      val harness = WorkbenchHarness()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      every { harness.latePage.removePartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      every { harness.latePage.removePartListener(any<IPartListener2>()) } just Runs
      harness.closeLateWindow()
      clearMocks(harness.latePage, answers = false)
      subject.uninstall()
      verify(exactly = 0) { harness.latePage.removePartListener(any<IPartListener2>()) }
    }

    // The drop must be scoped to the closing window. Keep-behaviour guard: passes before and
    // after this wave by design — pre-wave nothing dropped undetached pages at close at all;
    // post-wave the new walk applies the same membership rule as the `pages` walk. What it
    // discriminates is an over-eager drop: an undetached page whose window stays open must keep
    // its record, so uninstall still makes the extra detach attempt on it.
    it("windowClosed keeps the undetached page of a window that is not closing") {
      val harness = WorkbenchHarness()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      every { harness.latePage.removePartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      every { harness.latePage.removePartListener(any<IPartListener2>()) } just Runs
      harness.registeredWindowListener().windowClosed(mockk<IWorkbenchWindow>())
      clearMocks(harness.latePage, answers = false)
      subject.uninstall()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    // T1: the catch rollback. Keep-behaviour guard: passes before and after this wave by design —
    // it pins the catch's rollback, which both sides have. The injection has now moved TWICE,
    // each time because containment took its old point out of the catch's reach: first off the
    // page walk (`installPage.editorReferences`), then off `getWorkbenchWindows` (the enumeration
    // getter is contained since this wave — injected there, this test would go green while
    // covering nothing, for the second time). It injects at `addWindowListener`: of the two calls
    // that can still reach the catch (`PlatformUI.getWorkbench()` being the other) it is the only
    // one that can leave a listener half-registered for the rollback to take back off.
    it("a failed install takes the window listener back off") {
      val harness = WorkbenchHarness()
      every { harness.workbench.addWindowListener(any()) } throws RuntimeException("workbench going down")
      SecurityScanSaveListener().install()
      verify(exactly = 1) { harness.workbench.removeWindowListener(any()) }
    }

    // T1: the flag placement. Keep-behaviour guard: passes before and after this wave by design —
    // `installed` must stay false after a failed install so a later call really retries. Same
    // injection relocation as the rollback test above; the first `addWindowListener` call throws
    // and is counted all the same, so `exactly = 2` is one failed registration plus one retry.
    it("a failed install leaves the trigger retryable") {
      val harness = WorkbenchHarness()
      every { harness.workbench.addWindowListener(any()) } throws RuntimeException("workbench going down")
      val subject = SecurityScanSaveListener()
      subject.install()
      every { harness.workbench.addWindowListener(any()) } just Runs
      subject.install()
      verify(exactly = 2) { harness.workbench.addWindowListener(any()) }
    }

    // A7 secrecy at install's catch. With the enumeration getter contained (this wave), the
    // catch's reachable inputs are `PlatformUI.getWorkbench()` and `addWindowListener` only —
    // but the class-name-only rule still has to hold there, BEFORE anyone widens what the try
    // covers. The injection therefore moved again, from `getWorkbenchWindows` (whose throw now
    // stops at `contained`, where the containment secrecy tests already pin the rule and these
    // assertions would be satisfied vacuously) to `addWindowListener` — the same relocation the
    // two rollback tests above needed for the same reason. Keep-behaviour guard: passes before
    // and after this wave by design; what it pins is the catch's logging discipline, and it
    // fails when `log.warn(msg, e)` is restored there. Property 1 of 2: the class name is
    // recorded.
    it("a failed install records the failure's class name in the log message") {
      val harness = WorkbenchHarness()
      every { harness.workbench.addWindowListener(any()) } throws RuntimeException("workbench going down")
      val ilog = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      every { ilog.warn(capture(messages)) } just Runs
      every { Platform.getLog(any<Bundle>()) } returns ilog
      SecurityScanSaveListener().install()
      messages.any { it.endsWith("RuntimeException") } shouldBe true
    }

    // Property 2 of 2: what the failure's message quotes appears NOWHERE in what reached the log
    // — neither in a message string nor inside a throwable argument. Same relocation and same
    // keep-behaviour status as property 1; this is the test that fails when someone "restores"
    // `log.warn(msg, e)` at the install catch.
    it("a file path quoted by the failure under install does not reach the log") {
      val harness = WorkbenchHarness()
      every { harness.workbench.addWindowListener(any()) } throws RuntimeException("/home/user/secret/path.txt")
      val ilog = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      val throwables = mutableListOf<Throwable>()
      every { ilog.warn(capture(messages)) } just Runs
      every { ilog.warn(capture(messages), capture(throwables)) } just Runs
      every { Platform.getLog(any<Bundle>()) } returns ilog
      SecurityScanSaveListener().install()
      val reachedTheLog = messages + throwables.map { it.message.orEmpty() }
      reachedTheLog.none { "/home/user/secret/path.txt" in it } shouldBe true
    }

    // This wave: the enumeration GETTER was the one workbench call between the active-window walk
    // and `installed = true` still outside any containment. A throw there landed in install's
    // catch AFTER the active window had already attached: window listener rolled back, `installed`
    // never set, one call site, no retry — the pages and providers already attached sat live but
    // permanently inert, which is verbatim the state the previous wave was written to eliminate.
    // `installed` is not directly observable, so liveness is observed through the one gate that
    // reads it — a window opened later attaches only when install really ended installed.
    it("a throwing window enumeration does not leave the session inert for later windows") {
      val harness = WorkbenchHarness()
      every { harness.workbench.workbenchWindows } throws RuntimeException("workbench model failed")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { harness.latePage.addPartListener(subject) }
    }

    // The second property of surviving a throwing enumeration: the catch's rollback must NOT run.
    // Before this wave the catch took the window listener back off; contained, the failure must
    // leave the listener on — it is the only path by which future windows are ever observed.
    it("a throwing window enumeration keeps the window listener registered") {
      val harness = WorkbenchHarness()
      every { harness.workbench.workbenchWindows } throws RuntimeException("workbench model failed")
      SecurityScanSaveListener().install()
      verify(exactly = 0) { harness.workbench.removeWindowListener(any()) }
    }

    // Keep-behaviour guard: passes before and after this wave by design — the catch never rolled
    // pages back off, so the active window's attachment survived on both sides. It pins that the
    // containment keeps it that way: the active window attaches BEFORE the enumeration runs, and
    // a throw from the enumeration must not cost the user the window they are actually in.
    it("a throwing window enumeration keeps the active window's attachment intact") {
      val harness = WorkbenchHarness()
      every { harness.workbench.workbenchWindows } throws RuntimeException("workbench model failed")
      val subject = SecurityScanSaveListener()
      subject.install()
      verify(exactly = 1) { harness.installPage.addPartListener(subject) }
    }

    // B: registration bookkeeping. `providers.add` / `pages.add` recorded BEFORE the registration
    // call, and a throwing registration kept the record: every later partOpened/partActivated
    // found `add` answering false and skipped the attach, so saves through that provider went
    // silently unobserved for the whole session. These tests pin the repaired bookkeeping on both
    // instances of the shape — provider and page.

    it("a provider whose registration throws is registered again on the next part activation") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      every { provider.addElementStateListener(any()) } just Runs
      subject.partActivated(reference)
      verify(exactly = 2) { provider.addElementStateListener(subject) }
    }

    // The platform may have added the listener before throwing (the throw does not say how far
    // the call got), and a retry that succeeds must not end double-registered — so a failed
    // registration is taken back off the provider immediately, not left for uninstall.
    it("a provider whose registration throws is taken back off in case it was half-added") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      verify(exactly = 1) { provider.removeElementStateListener(subject) }
    }

    // When even that immediate detach throws, the listener may still be on the provider with no
    // record left in `providers` to walk — so the target is remembered separately and uninstall
    // makes one more attempt: `exactly = 2` is the immediate try plus uninstall's retry.
    it("a provider whose registration and immediate detach both throw is still detached at uninstall") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { provider.removeElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      subject.uninstall()
      verify(exactly = 2) { provider.removeElementStateListener(subject) }
    }

    // The corner the retry itself opens: when the failed registration's immediate detach ALSO
    // threw, the half-added listener may still be on the provider, and a retry that just added
    // again could end double-registered — every save would upload twice. So a retry of a target
    // remembered as undetached takes the listener off once more before re-registering:
    // `exactly = 2` on the remove is the failed immediate detach plus the retry's scrub.
    it("a successful retry after a failed registration and detach scrubs the half-added listener first") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { provider.removeElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      every { provider.addElementStateListener(any()) } just Runs
      every { provider.removeElementStateListener(any()) } just Runs
      subject.partActivated(reference)
      verify(exactly = 2) { provider.removeElementStateListener(subject) }
    }

    // A (this wave): the scrub itself can throw, and control must NOT fall through and register
    // on top of a possibly half-added listener — that is the double-upload direction the scrub
    // exists to prevent. Unreachable with the platform's own providers (all three dedupe their
    // add — see the bytecode notes on install()); reachable with a third-party provider that
    // neither dedupes nor detaches, which is exactly the world the scrub was written for.
    it("a retry whose scrub throws does not register on top of the half-added listener") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { provider.removeElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      every { provider.addElementStateListener(any()) } just Runs
      subject.partActivated(reference)
      verify(exactly = 1) { provider.addElementStateListener(subject) }
    }

    // The refusal above trades "duplicate uploads" for "unobserved until the provider's remove
    // recovers" — which only holds if the refusal really is retryable. `clearMocks` resets the
    // call counts so the assertion counts only what the healing delivery itself does.
    it("a retry blocked by a throwing scrub registers once the scrub succeeds") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { provider.removeElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      every { provider.addElementStateListener(any()) } just Runs
      subject.partActivated(reference)
      every { provider.removeElementStateListener(any()) } just Runs
      clearMocks(provider, answers = false)
      subject.partActivated(reference)
      verify(exactly = 1) { provider.addElementStateListener(subject) }
    }

    // A blocked retry must leave the target where uninstall's extra walk can still find it: the
    // half-added listener may be on the provider, and the blocked delivery proved the remove was
    // still throwing, so the memory has to survive until either a scrub or uninstall succeeds.
    it("a retry blocked by a throwing scrub leaves the target remembered for uninstall's extra detach") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { provider.removeElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      every { provider.addElementStateListener(any()) } just Runs
      subject.partActivated(reference)
      every { provider.removeElementStateListener(any()) } just Runs
      clearMocks(provider, answers = false)
      subject.uninstall()
      verify(exactly = 1) { provider.removeElementStateListener(subject) }
    }

    // B (this wave): the scrub sits AFTER the dedup check, and that placement had zero coverage.
    // Keep-behaviour guard: passes before and after this wave by design — since the scrub-failure
    // path stopped falling through (A), a registered target is never left in `undetached`, so the
    // state the placement protects (registered AND remembered as undetached) is no longer
    // reachable and "scrub above the dedup check" is an equivalent mutant on reachable states.
    // The test pins the observable property all the same: a delivery for a provider that a
    // successful retry settled must not touch its LIVE registration — the disaster if the
    // fall-through ever comes back while the scrub sits above the dedup check.
    it("a delivery for a provider settled by a successful retry does not detach it") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { provider.removeElementStateListener(any()) } throws RuntimeException("provider broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      every { provider.addElementStateListener(any()) } just Runs
      every { provider.removeElementStateListener(any()) } just Runs
      subject.partActivated(reference)
      clearMocks(provider, answers = false)
      subject.partActivated(reference)
      verify(exactly = 0) { provider.removeElementStateListener(any()) }
    }

    // `listenTo` runs `attach` once per editor reference on the page, and the registration call
    // runs third-party provider code — one broken provider must not abort the walk for the
    // editors behind it.
    it("one provider whose registration throws does not stop the walk from attaching the rest of the page") {
      val harness = WorkbenchHarness()
      val (badReference, badProvider) = harness.editorReference()
      val (goodReference, goodProvider) = harness.editorReference()
      every { badProvider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      every { harness.latePage.editorReferences } returns arrayOf(badReference, goodReference)
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { goodProvider.addElementStateListener(subject) }
    }

    // The page instance of the same shape. A second `windowOpened` for the same window is the
    // delivery vehicle, not the claim: the property is that a failed registration left no record
    // claiming success, so the next `listenTo` of the page really retries.
    it("a page whose part-listener registration throws is registered again on the next delivery") {
      val harness = WorkbenchHarness()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } just Runs
      harness.openLateWindow()
      verify(exactly = 2) { harness.latePage.addPartListener(subject) }
    }

    it("a page whose part-listener registration throws is taken back off in case it was half-added") {
      val harness = WorkbenchHarness()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { harness.latePage.removePartListener(subject) }
    }

    it("a page whose registration and immediate detach both throw is still detached at uninstall") {
      val harness = WorkbenchHarness()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      every { harness.latePage.removePartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      subject.uninstall()
      verify(exactly = 2) { harness.latePage.removePartListener(subject) }
    }

    // A failed page registration must still leave the page's already-open editors observed: the
    // element-state listeners attach straight to the providers and do not need the part listener.
    it("a page whose part-listener registration throws still gets its already-open editors attached") {
      val harness = WorkbenchHarness()
      val provider = harness.editorInLateWindow()
      every { harness.latePage.addPartListener(any<IPartListener2>()) } throws RuntimeException("page broken")
      val subject = SecurityScanSaveListener()
      subject.install()
      harness.openLateWindow()
      verify(exactly = 1) { provider.addElementStateListener(subject) }
    }

    // Keep-behaviour guard: passes before and after this wave by design — pre-wave the same
    // throw stopped at `partOpened`'s `contained`, whose class-name-only line the containment
    // secrecy test pins; post-wave it stops at the registration bookkeeping's own log line, which
    // this pins: it fails when that line is made to include `e.message` or the exception object.
    it("a file path quoted by a failed provider registration does not reach the log") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("/home/user/secret/path.txt")
      val ilog = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      val throwables = mutableListOf<Throwable>()
      every { ilog.warn(capture(messages)) } just Runs
      every { ilog.warn(capture(messages), capture(throwables)) } just Runs
      every { Platform.getLog(any<Bundle>()) } returns ilog
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      val reachedTheLog = messages + throwables.map { it.message.orEmpty() }
      reachedTheLog.none { "/home/user/secret/path.txt" in it } shouldBe true
    }

    // Keep-behaviour guard: passes before and after this wave by design — pre-wave `partOpened`'s
    // `contained` logged the class name, post-wave the registration bookkeeping does. It pins
    // that a failed registration is never silent: delete the bookkeeping's log line and nothing
    // records the failure (the throw no longer reaches `contained`), and this goes red.
    it("a failed provider registration still records the failure's class name in the log") {
      val harness = WorkbenchHarness()
      val (reference, provider) = harness.editorReference()
      every { provider.addElementStateListener(any()) } throws RuntimeException("provider broken")
      val ilog = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      every { ilog.warn(capture(messages)) } just Runs
      every { Platform.getLog(any<Bundle>()) } returns ilog
      val subject = SecurityScanSaveListener()
      subject.install()
      subject.partOpened(reference)
      messages.any { it.endsWith("RuntimeException") } shouldBe true
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
