package com.gitlab.eclipse.security

import com.gitlab.eclipse.ci.lint.sourceIdOf
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.IElementStateListener
import org.eclipse.ui.texteditor.ITextEditor

/**
 * How long a `File > Revert` is given to finish before its record is treated as debris.
 *
 * A revert is three callbacks that normally arrive in the same UI turn, so anything on this scale is
 * generous. It only has to be long enough that a slow revert is still recognised, and short enough
 * that a revert whose `elementContentReplaced` never arrives cannot suppress saves indefinitely.
 */
internal const val REVERT_WINDOW_MS = 30_000L

/**
 * Remembers which elements are in the middle of a `File > Revert`, so the dirty -> clean transition
 * a revert produces can be told apart from the one a save produces.
 *
 * Deliberately pure and free of SWT: this is the whole of the exclusion rule, and the rule is the
 * part that has to be provable rather than reviewable. Remote scanning uploads the file to the
 * user's GitLab instance, and a revert is not an action the user described as "save", so sending on
 * one would be outside what opting in covered.
 *
 * **The record is one-shot and is consumed by the dirty edge, not by `elementContentReplaced`.**
 * That is forced by the order the platform really delivers the three callbacks, which is not the
 * order the feature was first specified against. Verified from bytecode:
 * `org.eclipse.core.filebuffers-3.8.500` `ResourceFileBuffer.revert` calls
 * `handleFileContentChanged(true, false)` (@41-43), and
 * `ResourceTextFileBuffer.handleFileContentChanged` computes
 * `replaceContent = !newContent.equals(fDocument.get())` (@52-78; the equality check is reached
 * because `updateModificationStamp` — the `false` argument — fails the `ifne` at @52), then fires
 * `fireBufferContentAboutToBeReplaced` (@90) and `fireBufferContentReplaced` (@182) **only when
 * `replaceContent` is true** (guards at @80 and @172), and `fireDirtyStateChanged` (@257)
 * unconditionally — in that order; `org.eclipse.ui.editors-3.20.200`
 * `TextFileDocumentProvider$FileBufferListener` forwards all three synchronously without
 * reordering. So whenever the replacement callbacks do arrive, `elementContentReplaced` arrives
 * **before** the dirty edge that has to be suppressed — a guard that cleared the record there
 * would clear it a moment too early and upload the file the user just threw away. Any external
 * reload takes the same path (a branch switch, a refresh from disk), which is the same argument.
 *
 * **Accepted limitation — those guards defeat the exclusion in one case.** A `File > Revert` on a
 * buffer that is dirty but whose content already equals disk (type a character and delete it, or
 * undo back to the original, then revert) has `replaceContent == false`: neither replacement
 * callback fires and only the dirty edge arrives. This guard is then never armed,
 * [shouldScanOnClean] answers `true`, and that revert **is uploaded as if it were a save** —
 * exactly the action the paragraph above says opting in did not cover. Detecting the case needs
 * the buffer's content compared against disk, which this class deliberately cannot see, and
 * reading the file on the UI thread is not acceptable, so the exclusion is accepted as covering
 * only reverts that actually change the buffer.
 *
 * [nowMillis] is injected so the expiry window is testable without waiting for it.
 */
class RevertGuard(private val nowMillis: () -> Long = System::currentTimeMillis) {
  /** element key -> when the record was last stamped. */
  private val reverting = mutableMapOf<String, Long>()

  /** `elementContentAboutToBeReplaced`: a revert (or any wholesale replacement) is starting. */
  @Synchronized
  fun aboutToBeReplaced(key: String) {
    expireStale()
    reverting[key] = nowMillis()
  }

  /**
   * `elementContentReplaced`: the content is in, but the dirty edge it causes has not arrived yet,
   * so the record has to **survive** this call. It is re-stamped rather than left alone so that the
   * expiry window is measured from the last thing that actually happened — a revert slow enough to
   * cross the window between announcement and completion must not lose its guard on the way.
   */
  @Synchronized
  fun contentReplaced(key: String) {
    if (reverting.containsKey(key)) reverting[key] = nowMillis()
  }

  /**
   * Forgets [key] outright.
   *
   * For the endings a dirty edge will never follow — the element was deleted, or moved to a new
   * identity. Nothing is left that could consume the record, so it would otherwise sit there until
   * it expired.
   */
  @Synchronized
  fun discard(key: String) {
    reverting.remove(key)
  }

  /**
   * Answers the dirty -> clean transition: `true` when it was a save, `false` when it belongs to a
   * revert — and in that case **consumes** the record, so the very next dirty edge on the same
   * element is a real save again.
   *
   * Stale records are dropped here rather than on a timer, so nothing needs to be scheduled or
   * cancelled: a revert whose dirty edge never arrives at all stops suppressing saves as soon as
   * anything asks a question again.
   */
  @Synchronized
  fun shouldScanOnClean(key: String): Boolean {
    expireStale()
    return reverting.remove(key) == null
  }

  /** Number of live records. `internal`-by-name for tests; not part of the behaviour. */
  @Synchronized
  fun entryCountForTest(): Int = reverting.size

  private fun expireStale() {
    val cutoff = nowMillis() - REVERT_WINDOW_MS
    reverting.entries.removeAll { (_, recordedAt) -> recordedAt <= cutoff }
  }
}

/**
 * Stable key for an editor input, reusing the CI lint identity rule (`ActiveEditorContent.kt:34`):
 * workspace-relative full path for a file input, so the same basename in two projects cannot share
 * a revert record. Anything that is not an editor input has no key and is ignored.
 */
internal fun scanKeyOf(element: Any): String? = (element as? IEditorInput)?.let { sourceIdOf(it) }

/**
 * URI of an editor input in **exactly** the spelling the rest of the plugin sends the language
 * server: `IFile.locationURI.toASCIIString()`, copied from
 * `GitLabLanguageServerOpenFilesService.kt:66` (also `:89`, `:145`).
 *
 * Any other spelling would still be accepted by the server but the diagnostics it publishes back
 * could no longer be matched to the request that asked for them.
 */
internal fun scanUriOf(element: Any): String? =
  (element as? IFileEditorInput)?.file?.locationURI?.toASCIIString()

/**
 * Whether [element] is the input of the editor the user is currently in.
 *
 * `AbstractTextEditor` delivers these callbacks through `asyncExec`, so arriving on the UI thread is
 * likely but not guaranteed. Off it, `PlatformUtils.getActiveTextEditor()` resolves the workbench
 * window through a display it does not own and answers `null` whatever is really active — which
 * would read as "some other editor" and silently drop a save the user did make. A wrong answer that
 * looks like a real one is worse than an admitted refusal, so this says which of the two happened
 * and declines rather than inventing it.
 */
private fun activeEditorInputIsOnUiThread(element: Any): Boolean {
  if (Display.getCurrent() == null) {
    logger<SecurityScanSaveListener>()
      .warn("Security scan save trigger skipped: the save notification did not arrive on the UI thread.")
    return false
  }
  return service<PlatformUtils>().getActiveTextEditor()?.editorInput == element
}

/**
 * Runs a remote security scan when the user saves the file they are looking at.
 *
 * Three gates stand between a save and an upload, in this order (design §10.2):
 *  1. `scanFileOnSave` — the save trigger's own setting. **Nothing else in the plugin reads it**,
 *     so if it is not honoured here it is not honoured at all. It is checked here rather than in
 *     [SecurityScanLauncher] on purpose: the launcher is shared with the command, and an explicit
 *     command must not be silenced by a setting about saving.
 *  2. the [RevertGuard] — a revert is not a save.
 *  3. "is this the editor the user is actually in" — a `Save All` over twenty dirty editors must
 *     upload one file, not twenty. This is the gate that bounds it, and it is the last one because
 *     it is the only one that has to touch the workbench.
 *
 * The master `SECURITY_SCAN_ENABLED` gate is not re-checked here; [SecurityScanLauncher.launch]
 * evaluates it first and again under its outbound lock.
 *
 * Everything that touches the workbench is a constructor lambda with a production default, so the
 * decision above is reachable in a headless test while the SWT surface stays a thin shell.
 */
@Suppress("TooManyFunctions")
class SecurityScanSaveListener(
  private val guard: RevertGuard = RevertGuard(),
  private val scanOnSaveEnabled: () -> Boolean = {
    service<ScopedPreferenceStore>().getBoolean(PreferenceConstants.SECURITY_SCAN_ON_SAVE)
  },
  private val keyOf: (Any) -> String? = ::scanKeyOf,
  private val uriOf: (Any) -> String? = ::scanUriOf,
  private val isActiveEditorInput: (Any) -> Boolean = ::activeEditorInputIsOnUiThread,
  private val launch: (String?, SecurityScanSource) -> SecurityScanLaunchOutcome = { uri, source ->
    service<SecurityScanLauncher>().launch(uri, source)
  },
) : IElementStateListener, IPartListener2 {
  private val log by lazy { logger<SecurityScanSaveListener>() }

  /** Providers this listener is attached to, so [uninstall] can detach from exactly those. */
  private val providers = mutableSetOf<IDocumentProvider>()

  /** Pages this listener was added to as a part listener, for the same reason. */
  private val pages = mutableSetOf<IWorkbenchPage>()

  /**
   * Providers whose registration threw AND whose immediate best-effort detach threw too (see
   * [registerContained]): the listener may still be half-on them with no record in [providers],
   * so [uninstall] walks these as well and makes one more detach attempt — and, for the page
   * instance [undetachedPages], so does [onWindowClosed] for the pages a closing window
   * disposed. A later activation
   * still retries the registration, but through the scrub first: the possibly half-added
   * listener has to come off, and while that scrub keeps throwing the retry refuses to register
   * on top — unobserved-but-retryable, never double-registered (the in-body comment in
   * [registerContained]).
   */
  private val undetachedProviders = mutableSetOf<IDocumentProvider>()

  /** The page instance of the same bookkeeping, for [pages]. */
  private val undetachedPages = mutableSetOf<IWorkbenchPage>()

  /**
   * Pages whose editor walk ran to the end, recorded separately from [pages] because the two say
   * different things: [pages] records a successful `addPartListener`, and that succeeding does
   * NOT mean the walk behind it finished — `page.editorReferences` can throw after it. Only ever
   * recorded while the page is in [pages] ([listenTo]), so a record here always sits behind a
   * live part listener; a walk that completed while the registration was failing is deliberately
   * not remembered, because nothing would attach the editors opened after it. A page in [pages]
   * but not here has its part listener on and a walk that did not reach its end, and the editor
   * that was ALREADY active when the walk aborted is the one a mere refocus never hands back to
   * `partActivated`: it fired before the listener was on, and refocusing does not re-fire it —
   * shell activation runs `WorkbenchWindow$6.shellActivated` -> `fireWindowActivated` only
   * (verified from bytecode, 3.133.0 and 3.137.0 byte-identical), and on the e4 side
   * `ShellActivationListener.processWindow` -> `windowContext.activateBranch()` only (workbench.swt
   * 0.17.500/0.17.1000): the sole caller of `PartServiceImpl.firePartActivated` is
   * `activate(MPart,ZZ)` @438 (e4.ui.workbench 1.15.500/1.18.100), which nothing on that path
   * calls, and the context-injected `setPart` stops at its `activePart == part` identity guard
   * (@0-5) since window-scoped ACTIVE_PART (`ActivePartLookupFunction.compute` reads the asking
   * window's own `getActiveLeaf()`) does not change on a refocus. An actual change of active
   * part in that window DOES re-fire it: when the user activates another part and comes back,
   * `setPart` passes its identity guard and calls `activate(MPart,ZZ)` (@56/@66, both versions
   * above), whose only same-part early return (@203-208 -> @249) cannot be taken for a part that
   * is not currently the active one, so @438 fires — and [onPartSeen] -> [attach] then rerun the
   * whole lookup: nothing in that chain remembers the part, and [registerContained]'s dedup is
   * keyed on the resolved provider, which a lookup that threw never recorded. So
   * [onWindowActivated] is the one retry a mere refocus can trigger for that editor — the retry
   * that needs no part switch — and its gate consults this record alongside [pages].
   *
   * Residual, documented rather than closed: membership here records that the walk ENUMERATED
   * to its end, not that every editor on the page attached, so a page in [pages] AND here can
   * still hold unattached editors. [listenTo] contains each editor's attachment on its own, so
   * an editor whose `getPart`/`getAdapter`/`documentProvider` threw is skipped while the walk
   * still reaches its end and the record is written — and the gate then skips the page. A
   * skipped BACKGROUND editor waits only for its own first activation — an actual change of
   * active part, so `partActivated` fires for it and [attach] reruns the lookup; the editor
   * with no such trigger is the ACTIVE one: no refocus re-delivers `partActivated` to it
   * (the bytecode argument above) and this record keeps [onWindowActivated] off the page, so a
   * TRANSIENT failure on the active editor survives every window refocus — indefinitely, for a
   * user who stays in that editor — and heals on the first actual change of active part, when
   * the switch back re-fires `partActivated` and [attach] reruns the failed lookup (the
   * `setPart` -> `activate` path above). Closing even the refocus half — folding per-editor
   * success over the walk and gating the record on all-attached — would buy a page with one
   * persistently-broken editor a full re-walk (`getPart(false)` and `getAdapter`, third-party
   * code, once per open editor) plus one "editor attach" log line per broken editor on EVERY
   * window activation: a worse trade than the gap, the more so since the gap is, per skipped
   * editor, one part activation away — a first activation for a background one, a switch away
   * and back for the active one — not a session-long loss. It is also not a regression: before
   * the per-editor containment the same throw aborted the whole walk and the activation gate —
   * then `page in pages` alone — skipped the page the same way, while every editor the abort
   * left unattached already had this same recovery: a background editor on its own first
   * activation, the active one only after the same switch-away-and-return. What the containment
   * narrowed is how much a throw leaves unattached until those activations happen — every
   * editor behind the broken one, including the active one, before; only the editors whose own
   * lookups threw, now.
   *
   * Cleaned up wherever [pages] is: [onWindowClosed]'s membership walk and [uninstall].
   */
  private val walkedPages = mutableSetOf<IWorkbenchPage>()

  private var installed = false

  /**
   * Attaches to windows the user opens after [install] ran.
   *
   * `windowOpened` reads `activePage` directly because the page is already there when the callback
   * arrives. Verified from bytecode, not from memory: `org.eclipse.ui.workbench` (3.133.0, the
   * manifest floor, and 3.137.0 identically) `Workbench.createWorkbenchWindow` runs
   * `ContextInjectionFactory.inject` on the new `WorkbenchWindow` (@180) before
   * `fireWindowOpened` (@280); that inject invokes the `@PostConstruct` `WorkbenchWindow.setup()`,
   * which constructs the `WorkbenchPage` (@432-444) that `getActivePage()` returns as a plain
   * field. `createWorkbenchWindow` is the only caller of `fireWindowOpened`, so there is no path
   * that delivers this callback before the page exists.
   *
   * `windowActivated` is the retry vehicle for window-scoped attachment failures — see
   * [onWindowActivated] for what it may and may not cost.
   *
   * Every live callback runs through [contained]: the workbench delivers them via `SafeRunner`,
   * whose handler would log the full exception — and [attach] runs `getAdapter`, which can execute
   * third-party adapter factories whose message may quote a file path. Same secrecy rule as every
   * other callback in this class.
   */
  private val windowListener = object : IWindowListener {
    override fun windowOpened(window: IWorkbenchWindow) = contained("windowOpened") {
      onWindowOpened(window)
    }

    override fun windowClosed(window: IWorkbenchWindow) = contained("windowClosed") {
      onWindowClosed(window)
    }

    override fun windowActivated(window: IWorkbenchWindow?) = contained("windowActivated") {
      window?.let { onWindowActivated(it) }
    }

    override fun windowDeactivated(window: IWorkbenchWindow?) = Unit
  }

  /**
   * UI thread, under this listener's monitor (see the threading note on [install]). The
   * `installed` guard closes the teardown race: `fireWindowOpened` iterates a listener snapshot
   * taken by `EventManager.getListeners()` with no lock, so a delivery can arrive after
   * [uninstall] finished on the bundle-stop thread — attaching then would re-create exactly the
   * leak uninstall exists to prevent.
   */
  @Synchronized
  private fun onWindowOpened(window: IWorkbenchWindow) {
    if (!installed) return
    window.activePage?.let { listenTo(it) }
  }

  /**
   * UI thread, under the monitor; same teardown race and same `installed` guard as
   * [onWindowOpened].
   *
   * This is the retry vehicle for every window-or-page-scoped attachment that failed once and
   * had nothing to try it again: the enumeration getter throwing at [install] (already-open
   * background windows — which `windowOpened` structurally never fires for, see the note on
   * [install]'s enumeration), one enumerated window's walk throwing, [onWindowOpened]'s
   * `activePage` throwing, a page whose `addPartListener` threw (no part listener means
   * `partActivated` can never fire for it), and a page whose part listener made it on but whose
   * editor walk did not run to the end — `page.editorReferences` throwing, the one walk failure
   * [listenTo]'s per-editor containment cannot absorb. Focusing the window retries all five; for
   * the last, the ALREADY-ACTIVE editor is one `partActivated` reaches again only after an
   * actual change of active part, never on a mere refocus (see [walkedPages] for the bytecode on
   * both halves), so this retry is the one that reaches it without the user switching parts.
   *
   * The `page in pages && page in walkedPages` check is the cost gate, and it is what keeps the
   * steady state O(1): membership in [pages] is only ever recorded on a successful
   * `addPartListener` ([registerContained]), so an attached page has its part listener on and
   * `partActivated` already retries per editor there; membership in [walkedPages] says the
   * editor walk ENUMERATED to its end ([listenTo]) — not that every editor attached: the
   * per-editor containment skips an editor whose lookup threw while the walk still completes,
   * so a page behind this gate can still hold unattached editors (the residual on
   * [walkedPages]). The gate skips such a page on cost, not because a re-walk would find
   * nothing: re-walking on every activation would pay `getPart(false)` and `getAdapter` —
   * third-party code, under this monitor on the UI thread, once per open editor — to rerun at
   * most those editors' failed lookups, which `partActivated` already reruns on each one's next
   * actual activation (for the active editor, only after a switch away and back — the trade is
   * weighed in full on [walkedPages]). So a normal activation costs one contained call, one
   * monitor acquisition, one `activePage` read and two set lookups — no third-party code; only
   * a window whose page is not attached OR whose walk never reached its end — the failure
   * states above — pays the walk, once per activation until both records stick.
   *
   * Known-considered gap, recorded rather than guarded: unlike [onWindowClosed], this path makes
   * no disposal check. If the platform ever delivered a `windowActivated` for a window between
   * `hardClose`'s `fireWindowClosed` (@266) and its nulling of `page` (@350), the disposed page
   * would be recorded in [pages] and held until [uninstall]. No real Eclipse path that fires an
   * activation on a closing window could be constructed, so no guard is added on speculation;
   * this note exists so the question does not have to be rediscovered.
   */
  @Synchronized
  private fun onWindowActivated(window: IWorkbenchWindow) {
    if (!installed) return
    val page = window.activePage ?: return
    if (page in pages && page in walkedPages) return
    listenTo(page)
  }

  /**
   * Removes by membership in the tracked sets, not through `window.activePage`: on the platform's
   * own close path (`WorkbenchWindow.hardClose`, verified from bytecode in 3.133.0 and 3.137.0)
   * the model removal at @266 fires this callback while the page is still reachable, but on a
   * delayed delivery `WorkbenchPage.close(ZZ)` has already nulled `legacyWindow` (@613) and
   * `hardClose` has nulled `page` (@350) — so BOTH `window.activePage` and `page.workbenchWindow`
   * answer null by then. A page whose `workbenchWindow` is null is disposed and can belong to no
   * live window, so it is dropped no matter which window's close delivered the callback; detaching
   * from it is a no-op (`partListener2List` is never nulled, only cleared). Walking the tracked
   * sets also stops this callback from ever touching a page it never tried to attach to.
   */
  @Synchronized
  private fun onWindowClosed(window: IWorkbenchWindow) {
    detachPagesOfClosed(pages, window)
    // [undetachedPages] is teardown bookkeeping, and window-close is a teardown event: left out
    // of this walk, a page whose registration AND immediate detach both threw kept the disposed
    // window — and the editors it holds — strongly referenced until [uninstall], which then made
    // one more pointless detach on it. The one-more-attempt runs here instead, and the record is
    // dropped even when that attempt throws: the page is disposed either way, and keeping the
    // reference IS the leak.
    detachPagesOfClosed(undetachedPages, window)
    // The walk-completion record leaves with the page — same membership rule, no detach to make
    // (it records a fact, not a registration). Left behind, it is not only the same strong
    // reference as finding D above: were the platform ever to hand a later window the same page
    // instance, the stale record would claim its walk completed and the activation gate would
    // skip exactly the page a retry exists for.
    walkedPages.removeAll { page -> disposedBy(page, window) }
  }

  /** Drops from [tracked], and best-effort detaches, every page [window]'s close disposed. */
  private fun detachPagesOfClosed(tracked: MutableSet<IWorkbenchPage>, window: IWorkbenchWindow) {
    tracked.removeAll { page ->
      val closing = disposedBy(page, window)
      if (closing) runCatching { page.removePartListener(this) }
      closing
    }
  }

  /**
   * Whether [window]'s close disposed [page]: it belongs to [window], or belongs to no window at
   * all — a page whose `workbenchWindow` is null is disposed and can belong to no live window
   * (see the KDoc on [onWindowClosed]).
   *
   * A THROW from the lookup is not evidence of disposal — only a null owner is (nulled by
   * hardClose) — so `getOrDefault(false)` keeps the page rather than dropping it: dropping would
   * silently detach a live page, while keeping costs nothing because [uninstall] clears every
   * tracked set regardless. In production `getWorkbenchWindow()` is a bare field read, so the
   * failure arm is unreachable today; the distinction is kept so the collapse cannot be mistaken
   * for a decision.
   */
  private fun disposedBy(page: IWorkbenchPage, window: IWorkbenchWindow): Boolean =
    runCatching { page.workbenchWindow }
      .map { owner -> owner === window || owner == null }
      .getOrDefault(false)

  /**
   * Starts listening for saves. **Must be paired with [uninstall]** — the listener is held by every
   * document provider it attaches to, and those outlive this plugin's bundle.
   *
   * Idempotent. Never lets an `Exception` escape: every page walk runs inside [contained]
   * (`Throwable`), so no third-party code can throw into this frame at all, and what the catch
   * below can see is only the workbench-level calls it names. An `Error` raised by those platform
   * calls themselves is the one thing that can still propagate — it cannot carry a third-party
   * message, and the caller has its own last-resort guard. A workbench that is not up yet is not a
   * failure: the window listener does the attaching later.
   *
   * **Threading.** [install] runs on the UI thread (`GitLabEclipseStartup`, `display.syncExec`);
   * [uninstall] runs on the bundle-stop thread (`GitLabEclipseStartup.stop()` has no `syncExec` on
   * that path). Every mutation of [pages], [providers], their undetached twins and [installed] —
   * including the UI-thread callbacks [onWindowOpened], [onWindowClosed], [onWindowActivated] and
   * [onPartSeen] — therefore holds this listener's monitor. What was verified from bytecode (in the versions the manifest floor
   * resolves) is bounded to the listener add/remove calls this class makes to attach and detach:
   * those are lock-leaf. `Workbench.add/removeWindowListener` -> synchronized
   * `EventManager.add/removeListenerObject`, which only touches a `ListenerList`;
   * `WorkbenchPage.add/removePartListener` -> `ListenerList.add/remove`, themselves `public
   * synchronized` (`org.eclipse.equinox.common` 3.19.100) but lock-leaf all the same — their
   * bodies touch nothing but the array;
   * `AbstractDocumentProvider.add/removeElementStateListener` -> plain `List` ops;
   * `TextFileDocumentProvider.removeElementStateListener` -> `List.remove` (@9), conditionally
   * `TextFileBufferManager.removeFileBufferListener` (@27-36, a `monitorenter` on its own list
   * around one `List.remove`), then unconditionally
   * `getParentProvider().removeElementStateListener(listener)` (@41-46) — leaf for the default
   * lazily created `StorageDocumentProvider` parent (inherited `AbstractDocumentProvider` `List`
   * ops), but `setParentDocumentProvider` is public, so that last hop is only as leaf as the
   * wiring. What CANNOT be bounded is the path [attach] takes to reach the provider:
   * `getPart(false)`, `getAdapter` and `getDocumentProvider` run editor and adapter-factory code
   * — third-party (see [attach] and the note on [windowListener]) — under this monitor, on the
   * UI thread; no bytecode claim covers it. No inversion comes from the platform's side:
   * `fireWindowOpened`/`fireWindowClosed`/`fireWindowActivated` and
   * `WorkbenchPage.firePartOpened`/`firePartActivated` all iterate an unsynchronized listener
   * snapshot (`EventManager.getListeners()` / `ListenerList.iterator()`; `fireWindowActivated`
   * `getListeners` @1 then per-listener `SafeRunner.run` @41, `firePartActivated`
   * `partListener2List` iterator @109-112 then `SafeRunner.run` @144, verified in 3.133.0 and
   * 3.137.0 like the rest), so no thread ever holds a platform monitor while calling into this
   * class.
   */
  @Synchronized
  fun install() {
    if (installed) return
    try {
      val workbench = PlatformUI.getWorkbench()
      // The window listener is registered unconditionally, not as the fallback of an either/or: a
      // page that already exists says nothing about the windows the user opens later (`Window >
      // New Window`), and an install that only attached to today's page would leave every later
      // window's first editor of a type saving without a scan. Registering twice cannot happen —
      // `Workbench.addWindowListener` delegates to `ListenerList.add`, which returns without
      // adding when the same listener is already present (verified from bytecode, see the ordering
      // note on [windowListener]).
      workbench.addWindowListener(windowListener)
      // The active window comes FIRST and stands outside the enumeration below, so attaching to
      // the window the user is actually in never depends on how many other windows there are or
      // on their walks surviving. It is normally also in `getWorkbenchWindows()`; `listenTo` is
      // idempotent (`pages.add`), so the overlap costs nothing. Its walk is contained for the
      // same reason each enumerated window's is: it reaches `getAdapter` — third-party code —
      // and this window is the one whose editors are guaranteed materialised, so it is the one
      // where `getPart(false)` most reliably returns a part for a broken adapter factory to
      // throw from. Uncontained, that throw landed in the catch below and killed the feature
      // for the session (window listener rolled back, `installed` never set, no retry) — and a
      // third-party Error (`LinkageError` from a replaced bundle) escaped install() entirely,
      // to be wrapped by `Synchronizer.syncExec` into an `SWTException` whose `getMessage()`
      // appends `throwable.toString()` and logged upstream with the exception object.
      contained("install active window walk") {
        workbench.activeWorkbenchWindow?.activePage?.let { listenTo(it) }
      }

      // Windows that are already open and NOT active are the window listener's blind spot:
      // `Workbench.createWorkbenchWindow` returns at @288 WITHOUT calling `fireWindowOpened`
      // (@280 — control jumps over it) when the context already holds an `IWorkbenchWindow`,
      // which is exactly the set
      // `getWorkbenchWindows()` enumerates (verified from bytecode, 3.133.0 and 3.137.0). Eclipse
      // restores several windows after a restart, so the two sources are complementary and both
      // are needed. Each window's walk is contained on its own: it reaches `getAdapter` — i.e.
      // third-party code — once per window, and one broken background window must neither abort
      // the walk for the windows that are fine nor reach the catch below after pages were already
      // attached (that combination left every attached page permanently inert: `installed` stayed
      // false, the callbacks' `installed` guard dropped everything, and install() has exactly one
      // call site, so there was no retry). The GETTER is contained too, in its own outer wrapper:
      // it runs after the active window already attached, so a throw from it reaching the catch
      // re-created exactly the inert state above — rollback, `installed` false, no retry — to
      // punish a failure the install had already survived. Contained, a dead enumeration costs
      // only the background windows; the active window and every window opened later still work.
      contained("install window enumeration") {
        workbench.workbenchWindows.forEach { window ->
          contained("install window walk") { window.activePage?.let { listenTo(it) } }
        }
      }
      // Set LAST, and only after the window listener and the page walks above. `installed = true`
      // deliberately means "the window listener is registered" — NOT "every already-open window
      // (or even the active one) is attached": a contained failure of any walk above — the active
      // window's, one enumerated window's, or the enumeration itself — still ends installed,
      // because the feature staying alive for the
      // windows that did attach beats the whole feature being dead: `partActivated` retries
      // the provider attach for any window whose part listener did make it on, and
      // `windowActivated` retries the whole page attach for any window whose walk failed (see
      // [onWindowActivated]). Setting the flag up front instead would make a
      // first start that ran before the workbench existed permanently indistinguishable from a
      // successful one: the catch below would log, the flag would say "done", and the save
      // trigger would be dead for the rest of the session with nothing for the user to see.
      installed = true
    } catch (e: Exception) {
      // Never let the bundle's start() throw. The rollback is deliberately partial: only the
      // window listener is taken back off here. Anything the page walk managed to attach before
      // the failure is already recorded in [pages]/[providers], and [uninstall] walks both
      // unconditionally — independent of [installed] — so nothing leaks past teardown. The flag
      // stays false, and a retry re-attaches idempotently: `pages.add`/`providers.add` answer
      // false for what is already there, and `ListenerList.add` dedupes by identity.
      runCatching { PlatformUI.getWorkbench().removeWindowListener(windowListener) }
      // Class name only, never the exception object — same secrecy rule as [contained]. With
      // every page walk AND the window enumeration contained, the only inputs that can reach
      // this catch are `PlatformUI.getWorkbench()` and `addWindowListener` — the two calls whose
      // failure really does mean nothing can ever attach, which is what makes both the rollback
      // meaningful and "workbench unavailable" a true message. The
      // class-name-only rule is kept regardless: the discipline must already hold if anyone
      // widens what the `try` covers.
      log.warn("Security scan save trigger not installed: workbench unavailable: ${e::class.simpleName}")
    }
  }

  /**
   * Stops listening and releases every reference taken by [install].
   *
   * Each removal is contained: the workbench is usually half torn down by the time this runs, and
   * one provider that has already gone must not keep the rest attached. The walks iterate
   * snapshots ([toList]) as defence in depth: nothing may abort this method between the first
   * removal and the last `clear()`, because whatever stays attached outlives the bundle.
   *
   * The detaches deliberately run UNDER the monitor. Moving them out would break one deadlock
   * cycle — the bundle-stop thread holding this monitor while a third-party provider's removal
   * blocks — but not the symmetric one ([attach]'s `getAdapter` running third-party code under
   * the same monitor on the UI thread, see the threading note on [install]), so it was judged
   * churn for a strictly smaller win than it appears. A change that wants this risk gone must
   * move BOTH out from under the monitor, or neither.
   */
  @Synchronized
  fun uninstall() {
    installed = false
    runCatching { PlatformUI.getWorkbench().removeWindowListener(windowListener) }
    // The undetached sets are walked ON TOP of the recorded ones, deliberately without
    // de-duplication against them. Since a retry refuses to register while its scrub keeps
    // failing ([registerContained]), a target is never in both sets today — but the walk does
    // not lean on that invariant, because removing twice is the direction that cannot leak: a
    // spurious remove is a no-op list removal (see the bytecode notes on [install]), a missing
    // one outlives the bundle.
    (pages.toList() + undetachedPages.toList()).forEach { page -> runCatching { page.removePartListener(this) } }
    pages.clear()
    undetachedPages.clear()
    // Nothing to detach — the walk record is a fact, not a registration — but it must not
    // outlive the install cycle: carried across, it would claim a completed walk for a page the
    // next install re-attaches, and the activation gate would skip the page whose walk aborted.
    walkedPages.clear()
    (providers.toList() + undetachedProviders.toList()).forEach { provider ->
      runCatching { provider.removeElementStateListener(this) }
    }
    providers.clear()
    undetachedProviders.clear()
  }

  /** Only ever called under this listener's monitor: from [install], [onWindowOpened], [onWindowActivated]. */
  private fun listenTo(page: IWorkbenchPage) {
    registerContained(
      "page attach",
      page,
      pages,
      undetachedPages,
      { it.addPartListener(this) },
      { it.removePartListener(this) },
    )
    // Editors that were already open when this installed get no partOpened of their own. Walked
    // even when the page registration above failed: the element-state listeners attach straight
    // to the providers and observe saves without the part listener's help. Each editor's
    // attachment is contained ON ITS OWN: [attach] runs `getPart(false)`, `getAdapter` and
    // `getDocumentProvider` — third-party code — before its registerContained, and uncontained,
    // one editor throwing there hid every editor behind it, including the active one (Codex
    // round 6; see [walkedPages] for why no refocus — only an actual part switch — brings
    // `partActivated` back to the active one).
    page.editorReferences.forEach { reference -> contained("editor attach") { attach(reference) } }
    // Recorded strictly AFTER the walk's end, so the only failure the containment above lets
    // abort the walk — `page.editorReferences` itself throwing — leaves no completion record and
    // [onWindowActivated] retries the page. The record claims enumeration completion and no
    // more: an editor the containment above skipped stays unattached WITH the record written,
    // so [onWindowActivated]'s gate reads it as "the walk ended", never as "every editor
    // attached" (the residual on [walkedPages]). And recorded ONLY while the page is in [pages]:
    // immediately after [registerContained], that membership holds exactly when a successful
    // `addPartListener` stands — every failure arm rolls the record back off — so a record here
    // always sits behind a live part listener, and editors opened after the recorded walk really
    // do get a `partOpened` that attaches them. Written unconditionally, the record outlived the
    // failed registration it was written next to: the walk completed while `addPartListener`
    // threw, an editor then opened with no part listener to see it, and a later activation's
    // successful registration satisfied the OTHER half of [onWindowActivated]'s gate while its
    // own re-walk aborted — both halves satisfied by DIFFERENT attempts, the gate skipped the
    // page, and the in-between editor was left to its own next `partActivated`: no walk would
    // reach it again, and if it was the active editor that meant a part switch away and back,
    // never a mere refocus (see [walkedPages]). A
    // record, once written, is still never rolled back by a later aborted re-walk: [pages]
    // membership — and with it the live listener — persists from the moment of recording until
    // close or uninstall drops both sets together, so a completed walk stays completed for
    // exactly as long as its claim stays true.
    if (page in pages) walkedPages.add(page)
  }

  /**
   * Records [target] in [recorded] and registers this listener on it, surviving a registration
   * call that throws — both instances of the shape ([attach]'s provider, [listenTo]'s page) go
   * through here so they cannot drift apart again.
   *
   * Record-then-register, in that order, is deliberate and must not be flipped: recording first
   * means a platform call that adds the listener and THEN throws is never attached-but-unrecorded,
   * so teardown stays sound. What that order used to cost was the retry: the record survived the
   * throw and claimed success, so every later `partOpened`/`partActivated` found `add` answering
   * false and skipped the registration — saves through that provider went silently unobserved for
   * the whole session. So a throw now (1) rolls the record back off, which is what lets a later
   * delivery retry; (2) takes the listener best-effort back off [target], because the throw does
   * not say how far the call got and a retry that succeeds must not end double-registered; and
   * (3) when even that detach throws, remembers [target] in [undetached] so [uninstall] still
   * makes one more attempt — the two failures together are the only path on which a half-added
   * listener has no record left to walk — and so a later retry scrubs the half-add off before
   * registering again; a retry whose scrub also throws registers nothing and leaves the memory
   * standing, so the delivery after it starts from the scrub once more (the in-body comment
   * below). The throw itself is swallowed, not rethrown: [listenTo]
   * runs [attach] once per editor reference, and one broken provider must not abort the walk for
   * the rest of the page. Class name only in the log — same secrecy rule as [contained], and for
   * the same reason: the registration call is third-party code whose message may quote a path.
   */
  private fun <T : Any> registerContained(
    what: String,
    target: T,
    recorded: MutableSet<T>,
    undetached: MutableSet<T>,
    add: (T) -> Unit,
    remove: (T) -> Unit,
  ) {
    if (!recorded.add(target)) return
    // A target remembered as undetached may still hold the half-added listener from the earlier
    // attempt whose detach failed too. Registering again on top of it could end
    // double-registered — every save would be observed, and uploaded, twice — so the retry takes
    // the listener off once more FIRST, and when even that scrub throws it refuses to register
    // at all: the record is rolled back off so the next delivery starts from the scrub again,
    // and the target stays remembered, so [uninstall] still makes its extra attempt. That trades
    // "unobserved until the provider's remove stops throwing" — fully retryable — for never
    // registering blind on top of a possibly half-added listener, the double-upload direction
    // this feature treats as worst. The refusal is not logged: the delivery that put the target
    // here already logged its failure, and this path re-runs on every part activation for as
    // long as the remove keeps throwing.
    // This sits after the dedup check on purpose: on a target that is already registered, the
    // remove would strip the LIVE registration and the early return above would never restore
    // it. With the scrub-failure early return, a registered target is never left in [undetached]
    // — a successful retry clears the memory, a blocked one clears the record — so that pairing
    // is unreachable from here today; the ordering is kept so correctness does not depend on
    // that invariant staying true. It is also strictly safer than the hoisted-scrub ordering,
    // not merely equal to it: between the `recorded.add` above and the scrub's `onSuccess` below
    // the two sets transiently overlap, across a call into third-party `remove(target)` under
    // this REENTRANT monitor — a synchronous re-entry from that call would find the target in
    // both sets, and in this ordering that frame stops at `recorded.add == false` and returns
    // harmlessly.
    if (target in undetached) {
      if (runCatching { remove(target) }.onSuccess { undetached.remove(target) }.isFailure) {
        recorded.remove(target)
        return
      }
    }
    try {
      add(target)
    } catch (e: Throwable) {
      recorded.remove(target)
      runCatching { remove(target) }.onFailure { undetached.add(target) }
      runCatching { log.warn("Security scan save trigger failed in $what: ${e::class.simpleName}") }
    }
  }

  override fun partOpened(partRef: IWorkbenchPartReference) = contained("partOpened") {
    onPartSeen(partRef)
  }

  override fun partActivated(partRef: IWorkbenchPartReference) = contained("partActivated") {
    onPartSeen(partRef)
  }

  /**
   * UI thread, under the monitor — same race and same reasoning as [onWindowOpened]:
   * `WorkbenchPage.firePartOpened` iterates a `ListenerList` snapshot, so a delivery can arrive
   * after [uninstall] already walked [providers], and attaching then would leak for the session.
   * The guard cannot sit in [attach] itself: [install] attaches through it while [installed] is
   * still false, deliberately, so a failed install stays recorded as failed.
   */
  @Synchronized
  private fun onPartSeen(partRef: IWorkbenchPartReference) {
    if (!installed) return
    attach(partRef)
  }

  /**
   * Attaches to the document provider behind [partRef].
   *
   * Providers are shared between editors of the same kind, so the set both dedupes the attach and
   * is the list [uninstall] has to walk (together with [undetachedProviders] — see
   * [registerContained] for what a throwing registration leaves where).
   */
  private fun attach(partRef: IWorkbenchPartReference) {
    if (partRef !is IEditorReference) return
    // `false`: never force an editor to materialise just to listen to it.
    val part = partRef.getPart(false) ?: return
    val textEditor = part.getAdapter(ITextEditor::class.java) ?: (part as? ITextEditor) ?: return
    val provider = textEditor.documentProvider ?: return
    registerContained(
      "provider attach",
      provider,
      providers,
      undetachedProviders,
      { it.addElementStateListener(this) },
      { it.removeElementStateListener(this) },
    )
  }

  /**
   * Runs a listener callback so that nothing can escape into the platform's notification loop.
   *
   * `TextFileDocumentProvider$FileBufferListener` iterates its listeners with no exception table, so
   * one throw from here does not just lose this feature — it skips every listener after us,
   * including `AbstractTextEditor`'s own dirty handling, which leaves a stale `*` on the user's tab.
   * The bodies below reach Koin and the workbench, both of which can be gone while the workbench is
   * stopping, so this is a reachable path rather than a defensive flourish.
   *
   * Only the exception's class name is recorded: this feature's failures can quote a file path, and
   * no path may reach the log.
   */
  private inline fun contained(what: String, body: () -> Unit) {
    try {
      body()
    } catch (e: Throwable) {
      runCatching { log.warn("Security scan save trigger failed in $what: ${e::class.simpleName}") }
    }
  }

  /**
   * The one place a save can start an upload. [SecurityScanLauncher.launch] returns immediately and
   * does the sending on the plugin's own scope.
   */
  override fun elementDirtyStateChanged(element: Any?, isDirty: Boolean) = contained("dirtyStateChanged") {
    if (isDirty) return@contained
    val target = element ?: return@contained
    if (!scanOnSaveEnabled()) return@contained
    val key = keyOf(target) ?: return@contained
    if (!guard.shouldScanOnClean(key)) return@contained
    if (!isActiveEditorInput(target)) return@contained
    val uri = uriOf(target) ?: return@contained
    launch(uri, SecurityScanSource.SAVE)
  }

  override fun elementContentAboutToBeReplaced(element: Any?) = contained("contentAboutToBeReplaced") {
    val key = element?.let(keyOf) ?: return@contained
    guard.aboutToBeReplaced(key)
  }

  /**
   * The content is in, but the dirty edge it causes has not been delivered yet — see [RevertGuard]
   * for the bytecode-verified ordering. The record must survive this call; it is the dirty edge
   * that consumes it.
   */
  override fun elementContentReplaced(element: Any?) = contained("contentReplaced") {
    val key = element?.let(keyOf) ?: return@contained
    guard.contentReplaced(key)
  }

  override fun elementDeleted(element: Any?) = contained("elementDeleted") {
    // A deleted element is not a save, and no dirty edge will ever arrive to consume a record left
    // against it, so this ending has to drop it outright.
    val key = element?.let(keyOf) ?: return@contained
    guard.discard(key)
  }

  override fun elementMoved(originalElement: Any?, movedElement: Any?) = contained("elementMoved") {
    // Same reasoning as elementDeleted: the old identity is gone, so nothing can consume a record
    // held against it.
    val key = originalElement?.let(keyOf) ?: return@contained
    guard.discard(key)
  }
}
