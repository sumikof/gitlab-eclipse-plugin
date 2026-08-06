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
 * `handleFileContentChanged(true, false)`, and `ResourceTextFileBuffer.handleFileContentChanged`
 * fires `fireBufferContentAboutToBeReplaced` (@90), `fireBufferContentReplaced` (@182) and
 * `fireDirtyStateChanged` (@257) unconditionally in that order;
 * `org.eclipse.ui.editors-3.20.200` `TextFileDocumentProvider$FileBufferListener` forwards all
 * three synchronously without reordering. So `elementContentReplaced` arrives **before** the dirty
 * edge that has to be suppressed — a guard that cleared the record there would clear it a moment
 * too early and upload the file the user just threw away. Any external reload takes the same path
 * (a branch switch, a refresh from disk), which is the same argument.
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
   */
  private val windowListener = object : IWindowListener {
    override fun windowOpened(window: IWorkbenchWindow) {
      window.activePage?.let { listenTo(it) }
    }

    override fun windowClosed(window: IWorkbenchWindow) {
      window.activePage?.let { page ->
        page.removePartListener(this@SecurityScanSaveListener)
        pages.remove(page)
      }
    }

    override fun windowActivated(window: IWorkbenchWindow?) = Unit

    override fun windowDeactivated(window: IWorkbenchWindow?) = Unit
  }

  /**
   * Starts listening for saves. **Must be paired with [uninstall]** — the listener is held by every
   * document provider it attaches to, and those outlive this plugin's bundle.
   *
   * Idempotent, and never throws: a workbench that is not up yet simply leaves the window listener
   * to do the attaching later.
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
      workbench.activeWorkbenchWindow?.activePage?.let { listenTo(it) }
      // Set LAST, and only after both the window listener and today's page (when there is one) are
      // really attached. Setting it up front would make a first start that ran before the
      // workbench existed permanently indistinguishable from a successful one: the catch below
      // would log, the flag would say "done", and the save trigger would be dead for the rest of
      // the session with nothing for the user to see.
      installed = true
    } catch (e: Exception) {
      // Never let the bundle's start() throw. A failed install leaves the workbench as it found
      // it: if the window listener made it on before the failure it is taken back off here, so
      // nothing is attached and nothing leaks, and a later call can retry — which is exactly what
      // the flag placement above preserves.
      runCatching { PlatformUI.getWorkbench().removeWindowListener(windowListener) }
      log.warn("Security scan save trigger not installed: workbench unavailable.", e)
    }
  }

  /**
   * Stops listening and releases every reference taken by [install].
   *
   * Each removal is contained: the workbench is usually half torn down by the time this runs, and
   * one provider that has already gone must not keep the rest attached.
   */
  @Synchronized
  fun uninstall() {
    installed = false
    runCatching { PlatformUI.getWorkbench().removeWindowListener(windowListener) }
    pages.forEach { page -> runCatching { page.removePartListener(this) } }
    pages.clear()
    providers.forEach { provider -> runCatching { provider.removeElementStateListener(this) } }
    providers.clear()
  }

  private fun listenTo(page: IWorkbenchPage) {
    if (pages.add(page)) page.addPartListener(this)
    // Editors that were already open when this installed get no partOpened of their own.
    page.editorReferences.forEach { attach(it) }
  }

  override fun partOpened(partRef: IWorkbenchPartReference) = attach(partRef)

  override fun partActivated(partRef: IWorkbenchPartReference) = attach(partRef)

  /**
   * Attaches to the document provider behind [partRef].
   *
   * Providers are shared between editors of the same kind, so the set both dedupes the attach and
   * is the exact list [uninstall] has to walk.
   */
  private fun attach(partRef: IWorkbenchPartReference) {
    if (partRef !is IEditorReference) return
    // `false`: never force an editor to materialise just to listen to it.
    val part = partRef.getPart(false) ?: return
    val textEditor = part.getAdapter(ITextEditor::class.java) ?: (part as? ITextEditor) ?: return
    val provider = textEditor.documentProvider ?: return
    if (providers.add(provider)) provider.addElementStateListener(this)
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
