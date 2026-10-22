package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import com.gitlab.eclipse.currentbranch.CurrentBranchStateService
import com.gitlab.eclipse.currentbranch.WindowEditorFileReader
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.mergerequests.diff.MrFileDiffEntryPoint
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionsLoader
import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.issues.ViewRefreshState
import com.gitlab.eclipse.views.sidebar.queries.CustomQueriesRefresh
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryRunner
import com.gitlab.eclipse.views.sidebar.queries.QueryProject
import com.gitlab.eclipse.views.sidebar.queries.SidebarQueriesController
import com.gitlab.eclipse.views.sidebar.queries.staleQueryRootSectionIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jface.action.MenuManager
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.jface.viewers.ITreeViewerListener
import org.eclipse.jface.viewers.TreeExpansionEvent
import org.eclipse.jface.viewers.TreeViewer
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.part.ViewPart
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.GlobalContext
import java.net.URI

/**
 * GitLab sidebar: a [TreeViewer] over one root per configured sidebar query (ISSUE-01; built by
 * [SidebarViewModel.buildQueryRoot] in config order) plus the "For current
 * branch" section (open MR for the checked-out branch, the issues it would close, and the
 * branch's latest pipeline as a Pipeline→Stage→Job subtree).
 *
 * Threading: the current-branch section comes from this window's shared state (status-bar design
 * §4.4: [CurrentBranchSidebarBinding] binds each of its refreshes to the generation's slots), and
 * the queries job runs on the shared IO [CoroutineScope] owned by [SidebarQueriesController]
 * (ISSUE-01 §8 F1). Each settles its slot in the generation's [RefreshSlots] then re-composes the whole tree
 * incrementally via [SidebarRefreshCoordinator], hopping to the SWT UI thread with `asyncExec`.
 * Stale generations ([ViewRefreshState]), stale section epochs ([ApplyScope]) and disposed widgets
 * are dropped. The slots outlive the fetch (UI thread only), so a [SidebarViewMode] toggle
 * re-composes without re-fetching (R8), even while some slots are still Pending. A change of the
 * custom queries setting re-runs the queries job only ([SidebarQueriesController.refreshQueries]).
 *
 * MR nodes expand lazily ([loadMrChildren]): first expansion fetches the MR's latest diff
 * version and renders an "Overview" node plus its changed files (flat in LIST mode,
 * folder hierarchy in TREE mode), under the same threading discipline.
 */
// TooManyFunctions: the discussions wiring (Task 9) adds the loader's UI-thread helpers and the
// section lookup, pushing this class past detekt's 11-function threshold. Suppressed rather than
// restructured: each helper closes over `viewer`/`logger`/`viewModel`, so moving them top-level
// would mean threading those through as parameters — more code and more state to get wrong on a
// class whose whole point is owning that widget state.
@Suppress("TooManyFunctions")
class GitLabSidebarView : ViewPart() {
  companion object {
    /** Must match the view id declared in plugin.xml. */
    const val VIEW_ID = "com.gitlab.eclipse.views.GitLabSidebarView"
  }

  private val logger = logger<GitLabSidebarView>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val mergeRequestService by lazyService<MergeRequestService>()
  private val currentUserService by lazyService<CurrentUserService>()
  private val preferenceStore by lazyService<ScopedPreferenceStore>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val viewState by lazyService<SidebarViewState>()

  private val viewModel = SidebarViewModel()
  private val refreshState = ViewRefreshState()

  // Composes viewer input from one generation's RefreshSlots. UI thread only: its memo
  // state is unsynchronized, and compose() is only ever called inside asyncExec.
  private val coordinator by lazy { SidebarRefreshCoordinator(viewModel) }

  // Deliberately not Koin singles (see RepositoryContextResolver KDoc); lazy so that
  // their `service()` default arguments resolve only once the workbench is up.
  private val repositoryContextResolver by lazy { RepositoryContextResolver() }
  private val queryRunner by lazy { CustomQueryRunner(apiClient, currentUserService, log = { logger.warn(it) }) }
  private val queriesRefresh by lazy {
    CustomQueriesRefresh(queryRunner, captureConnection = { apiClient.captureConnection() }, log = { logger.warn(it) })
  }
  private val jobService by lazy { JobService() }
  private val bridgeService by lazy { BridgeService() }

  private lateinit var viewer: TreeViewer

  /**
   * CI-06 §4.6: lazy, refresh-safe loading of the downstream pipeline nodes. SWT-free and tested
   * headless ([DownstreamLoader]); this view supplies only the ports, each used on the thread its
   * KDoc names: the IO launch on the shared scope, the `asyncExec` hop ([runOnUiIfAlive], which drops
   * the block once the control is disposed), and the viewer reads / refreshes (UI thread). Built
   * lazily because the ports capture [viewer]; dispose() cancels it only if it was ever created.
   */
  private val downstreamLoaderLazy = lazy {
    DownstreamLoader(
      launchOffUi = { block -> coroutineScope.launch { block() } },
      uiDispatch = { block -> runOnUiIfAlive(block) },
      source = ServiceDownstreamSource(apiClient, jobService, bridgeService),
      tree = object : DownstreamTree {
        override fun roots(): List<SidebarNode> = (viewer.input as? List<*>).orEmpty().filterIsInstance<SidebarNode>()

        override fun isDisposed(): Boolean = viewer.control.isDisposed

        override fun isExpanded(node: SidebarNode): Boolean = viewer.getExpandedState(node)

        // A refresh re-creates the items of children that are new instances collapsed, so the expansion
        // under a node whose children the sweep rebuilt is carried over by stable key, parents first
        // (expandedElements is pre-order). Children carried over by the transfer are the same
        // instances and keep their items as they are.
        override fun refreshSubtree(node: DownstreamPipelineNode, oldChildren: List<SidebarNode>?) {
          if (viewer.control.isDisposed) return
          val expanded = if (oldChildren == null) emptyList() else viewer.expandedElements.toList()
          viewer.refresh(node)
          if (oldChildren != null) {
            expanded
              .mapNotNull { remapInChildren(it, oldChildren, node.children) }
              .forEach { viewer.setExpandedState(it, true) }
          }
        }
      },
      buildChildren = viewModel::buildDownstreamChildren,
      warnLog = { logger.warn(it) },
    )
  }
  private val downstreamLoader by downstreamLoaderLazy

  // ISSUE-01 §8: the query side (setting listener, queries job on the shared scope, and the result cache),
  // SWT-free and tested headless. This view only supplies the SWT ports: the
  // UI hop and applyCompose. Started in createPartControl; disposed in dispose() only if it was ever created.
  private val queriesLazy = lazy {
    SidebarQueriesController(
      scope = coroutineScope,
      store = preferenceStore,
      work = { list, reuse, project, isActive, onSettled ->
        queriesRefresh.run(list, reuse, project, isActive, onSettled)
      },
      // Called inside the queries job (IO): blocking JGit, with the editor file captured on the UI thread.
      resolveProject = { file ->
        repositoryContextResolver.resolveNonInteractive(file)?.let { QueryProject(it.projectId) }
      },
      uiHop = { block -> runOnUiIfAlive(block) },
      apply = { slots, scope, update -> applyCompose(slots, scope, update = update) },
      currentSlots = { currentSlots },
      activeEditorFile = { windowEditorFile() },
      fullRefresh = { refreshGeneration(initial = true) },
      log = { logger.warn(it) },
    )
  }
  private val queries by queriesLazy

  // Status bar §4.4: the "For current branch" section is a subscriber of this window's shared state (fetched,
  // auto-refreshed and owned by CurrentBranchStateService); this view binds each of its refreshes to the generation's
  // slots. UI thread only: attached (state resolved, subscribed) in createPartControl, detached in dispose().
  private val currentBranchBindingLazy = lazy {
    CurrentBranchSidebarBinding(
      currentSlots = { currentSlots },
      // CI-06 §4.6 R2-4: the previous refresh's downstream re-fetch is superseded by this one's.
      cancelSweep = { downstreamLoader.cancelSweep() },
      apply = { slots, scope, soft, update -> applyCompose(slots, scope, softRebuild = soft, update = update) },
      // CI-06 §4.6 R1-2: once per refresh, after its applies (FIFO asyncExec), under the same gate.
      requestSweep = { slots, scope ->
        downstreamLoader.requestSweep { slots.acceptsApply(refreshState::isCurrent, scope) }
      },
      log = { logger.warn(it) },
    )
  }
  private val currentBranchBinding by currentBranchBindingLazy

  // The live refresh generation's slots, written on the UI thread in refresh() and mutated
  // on the UI thread inside applyCompose (RefreshSlots' contract). `null` = nothing was
  // ever fetched — onModeChanged then starts the first refresh instead of recomposing.
  private var currentSlots: RefreshSlots? = null

  // Latest MR diff version per merge request, UI thread only. Keyed by [mrCacheKey]: the MR's
  // web URL — globally unique across instances — rather than the numeric (projectId, iid) pair,
  // which is unique only WITHIN one instance: two instances can each hold a project 42 with an
  // MR !7, and under the numeric key a settings change racing a refresh could hand one
  // instance's cached diff version (and connection tags below) to the other's node. Within
  // a single instance the web URL is one-to-one with the numeric pair, so this changes
  // nothing there. An MR whose web URL is missing at runtime falls back to a per-MR numeric
  // key instead of the one degenerate URL value all such MRs would otherwise share — see
  // [mrCacheKey] for why that fallback is safe. Survives mode toggles — re-expanding an MR
  // re-composes its children in the new mode without re-fetching — and is cleared at the
  // start of a full refresh, so refreshed sidebars pick up new diff versions while the
  // several incremental composes of one refresh keep sharing it.
  private val mrVersionCache = mutableMapOf<String, GitLabMrVersion?>()

  // The two non-secret connection tags each cached diff version was actually fetched over, keyed
  // exactly like mrVersionCache and cleared with it (UI thread only). Recorded once on the fetch
  // path — where the capture already happens off the UI thread — so the cache-hit path never has
  // to capture: `captureConnection` reads the token, and for an expired OAuth credential that
  // performs a SYNCHRONOUS refresh request (OAuthTokenProvider.getToken -> refreshIfExpired),
  // which on the UI thread freezes the workbench. Correctness, not just cost: the tags exist to
  // record which instance AND which account the cached data came from, so re-capturing later
  // would stamp the node with whatever connection is configured now and hide the very mismatch
  // they are meant to expose. Deliberately a separate map: mrVersionCache's value type is part of
  // the existing cache-hit contract (`containsKey` distinguishes "fetched null" from "not
  // fetched") and is left untouched.
  private val mrConnectionTagsCache = mutableMapOf<String, Pair<String?, String?>>()

  // MR nodes with a version fetch in flight (UI thread only; identity-keyed since
  // MergeRequestNode does not override equals), so collapse/re-expand while a fetch is
  // running does not start a duplicate fetch.
  private val mrLoadsInFlight = mutableSetOf<MergeRequestNode>()

  // Stored so dispose() can remove this exact instance from the shared SidebarViewState.
  private val modeListener: () -> Unit = { onModeChanged() }

  /**
   * Lazy-loads a [DiscussionsSectionNode]'s threads on expansion. Built lazily for the same two
   * reasons as [pipelineService]/[jobService] — its `service()` constructor defaults must resolve
   * only once the workbench (and Koin) is up — plus a third: every function injected below
   * captures [viewer], which exists only after [createPartControl].
   *
   * **None of the injected functions may throw.** The loader calls them inside its terminal
   * branch, past the point where exactly one `onOutcome` call is guaranteed, so an exception
   * escaping a builder, [refreshNode] or [notify] would abort that branch and strand the callback
   * — the one thing [DiscussionsLoader]'s completion contract promises cannot happen. Each is
   * therefore made total by [guarded]: a disposed control is checked before it is touched, and
   * anything else that can fail (notably the notification popup, which needs an active window) is
   * caught and logged with `exceptionType=` only — never the exception object, which can carry a
   * token in its message.
   */
  private val discussionsLoader by lazy {
    DiscussionsLoader(
      runInBackground = { block -> guarded("runInBackground", Unit) { coroutineScope.launch { block() } } },
      runOnUi = { block -> runOnDiscussionsUiThread(block) },
      buildChildren = { node, result ->
        guarded("buildChildren", emptyList()) { viewModel.buildDiscussionChildren(node, result) }
      },
      buildFailureChildren = { _, gateRejected ->
        guarded("buildFailureChildren", emptyList()) { viewModel.buildDiscussionFailureChildren(gateRejected) }
      },
      buildLoadingChildren = {
        guarded("buildLoadingChildren", emptyList()) { viewModel.buildDiscussionLoadingChildren() }
      },
      refreshNode = { node ->
        guarded("refreshNode", Unit) { if (!viewer.control.isDisposed) viewer.refresh(node) }
      },
      // showOnUiThread, not show: `show` only SCHEDULES the popup in a later asyncExec runnable,
      // which would run outside this guard and send any failure to SWT's default handler instead
      // of producing the exceptionType= audit line. The loader calls `notify` on the UI thread
      // already, so opening the popup synchronously here keeps it inside the guard.
      notify = { message -> guarded("notify", Unit) { NotificationUtils.showOnUiThread(message) } },
    )
  }

  override fun createPartControl(parent: Composite) {
    viewer = TreeViewer(parent, SWT.SINGLE or SWT.H_SCROLL or SWT.V_SCROLL)
    viewer.contentProvider = SidebarContentProvider()
    viewer.labelProvider = SidebarLabelProvider()
    viewer.tree.headerVisible = false
    // Publish the tree selection to the workbench so selection-based command handlers
    // (e.g. CheckoutMrBranchHandler via HandlerUtil.getCurrentSelection) can see it.
    site.selectionProvider = viewer

    // Context menu: registered under the view id (registerContextMenu's one-arg form), so
    // plugin.xml menuContributions with locationURI "popup:<view id>" populate it. The
    // manager rebuilds on every open (removeAllWhenShown), which is what lets the
    // contributions' visibleWhen expressions re-evaluate against the current selection.
    val menuManager = MenuManager()
    menuManager.setRemoveAllWhenShown(true)
    viewer.control.menu = menuManager.createContextMenu(viewer.control)
    site.registerContextMenu(menuManager, viewer)

    viewer.addDoubleClickListener { event ->
      val node = (event.selection as? IStructuredSelection)?.firstElement as? SidebarNode
      // Inline rather than a member (keeps the class under the TooManyFunctions threshold).
      when (val action = sidebarDoubleClick(node)) {
        is SidebarDoubleClick.OpenDiff -> MrFileDiffEntryPoint.open(action.node)
        is SidebarDoubleClick.OpenUrl ->
          try {
            PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(URI.create(action.url).toURL())
          } catch (e: Exception) {
            logger.error("Failed to open URL: ${action.url}", e)
          }
        SidebarDoubleClick.None -> Unit
      }
    }

    viewer.addTreeListener(
      object : ITreeViewerListener {
        override fun treeExpanded(event: TreeExpansionEvent) {
          // Discussions section: fetch on first expansion and after a failed one (a retry), but
          // never while a fetch is in flight — collapsing and re-expanding mid-load must not
          // start a redundant second fetch — and never when already LOADED, which is both the
          // loader's own behavior for force = false and what keeps re-expansion cheap.
          val section = event.element as? DiscussionsSectionNode
          if (section != null) {
            val state = section.loadState
            if (state == DiscussionLoadState.NOT_LOADED || state == DiscussionLoadState.FAILED) {
              discussionsLoader.loadDiscussions(section, force = false, onOutcome = {})
            }
            return
          }
          // CI-06: a downstream pipeline loads its stages on first expansion, and retries after an
          // unconfirmed connection (FAILED); a LOADING or LOADED node is skipped by the loader, so
          // collapse / re-expand never starts a second fetch.
          val downstream = event.element as? DownstreamPipelineNode
          if (downstream != null) {
            downstreamLoader.load(downstream)
            return
          }
          val node = event.element as? MergeRequestNode ?: return
          if (node.loadedChildren == null) loadMrChildren(node)
        }

        override fun treeCollapsed(event: TreeExpansionEvent) = Unit
      },
    )

    viewState.addListener(modeListener)
    queries.start()
    subscribeCurrentBranch()
    refreshGeneration(initial = true)
  }

  /**
   * ANY thread (the queries setting listener): runs [block] on the UI thread unless the control is disposed — checked
   * before scheduling and again in the runnable. A Display disposed while scheduling (SWTException), as in
   * [applyCompose], means there is nothing left to refresh.
   */
  private fun runOnUiIfAlive(block: () -> Unit) {
    val control = viewer.control
    if (control.isDisposed) return
    try {
      control.display.asyncExec { if (!control.isDisposed) block() }
    } catch (@Suppress("SwallowedException") e: SWTException) {
      // Disposed between the check and the scheduling: nothing to refresh.
    }
  }

  /**
   * Status bar §4.4: subscribes [currentBranchBinding] to this window's shared current-branch state (created on demand:
   * the view may open before the plugin-start activation ran). Without a state the section settles as "no repository".
   */
  private fun subscribeCurrentBranch() {
    currentBranchBinding.attach {
      GlobalContext.getOrNull()?.getOrNull<CurrentBranchStateService>()?.ensureWindow(site.workbenchWindow)
    }
  }

  /** The Refresh button (and the CI write handlers): a full refresh, including the window's current branch. */
  fun refresh() {
    refreshGeneration(initial = false)
  }

  /**
   * Starts a new refresh generation. [initial] (opening the view, or a first refresh triggered before anything was
   * fetched) shows the window's current-branch state as it is (seeded, adopted or fetched once:
   * [CurrentBranchSidebarBinding.initial]); otherwise the current branch is re-fetched in full (manual Refresh).
   * The queries side and the caches are handled the same way in both.
   */
  private fun refreshGeneration(initial: Boolean) {
    val generation = refreshState.begin()
    // Captured here — this always runs on the UI thread (createPartControl, the refresh
    // handler, onModeChanged's asyncExec) — because the active-editor read goes through the
    // workbench, which silently yields null off the UI thread. Only this cheap workbench read
    // stays on the UI thread; the blocking JGit resolution runs inside the queries job.
    // Non-interactive by design: an ambiguous workspace resolves to null.
    val activeEditorFile = windowEditorFile()
    val slots = RefreshSlots(generation)
    currentSlots = slots
    // A full refresh eventually rebuilds every MR node (the new generation resets the
    // coordinator's memo); drop the cached diff versions once at refresh START — not per
    // compose — so the next expansion re-fetches new diffs while this one refresh's
    // several incremental composes keep sharing the cache.
    mrVersionCache.clear()
    // Cleared with the versions it labels: a tag must never outlive the cached data it describes.
    mrConnectionTagsCache.clear()
    // A full refresh rebuilds every DiscussionsSectionNode of THIS view's tree (new instances,
    // new nodeIds), so this view's old keys could only accumulate as dead entries — and any of
    // this view's in-flight loads must not touch its rebuilt tree; dropping exactly those keys
    // makes them report Superseded. Scoped to the nodes this view is about to replace because
    // the registry is process-wide and another workbench window's sidebar owns its own keys:
    // clearing those too would make that view's in-flight load report Superseded with no newer
    // load coming, stranding its section in LOADING (the expansion listener skips LOADING).
    DiscussionGenerationRegistry.clearLatestFor(
      collectDiscussionSectionNodeIds((viewer.input as? List<*>).orEmpty().filterIsInstance<SidebarNode>()),
    )
    // CI-06 §4.6: a full refresh stops every downstream load and the sweep; the rebuilt tree reloads
    // what is still expanded.
    downstreamLoader.cancelAll()
    // The current-branch unit: bound to these slots (applies and the downstream sweep) by the binding; the window
    // state's own refresh supersedes or coalesces as the design says (§4.1), and reports its errors itself.
    currentBranchBinding.refresh(initial, slots)
    // The query side of this refresh (UI thread): decodes the setting into slots.queries — before any apply, which
    // all run later via asyncExec — clears the result cache and replaces the queries job.
    queries.onFullRefresh(slots, activeEditorFile)
  }

  /** R16/R17: this window's active editor file (never another window's), UI thread only. */
  private fun windowEditorFile() = WindowEditorFileReader.activeEditorFile(site.workbenchWindow)

  /**
   * Schedules one incremental apply on the UI thread: run [update] (the settling unit's
   * slot mutation — [RefreshSlots] is mutated on the UI thread only, which is what keeps
   * the two current-branch sub-tasks from tearing each other's [Resolved] halves), then
   * re-compose via the coordinator and swap `viewer.input`. Stale generations and disposed
   * widgets are dropped, [update] included — a superseded refresh must never touch a newer
   * generation's tree. Also composes with the mode read here, on the UI thread at apply
   * time, so a mode toggle racing a fetch never paints nodes composed for a stale mode.
   *
   * Expansion (R9): the coordinator's memo keeps unchanged INNER nodes identity-stable
   * (an expanded [MergeRequestNode] and its in-flight lazy fetch survive by themselves),
   * so only the rebuilt top-level nodes need remapping by stable key
   * ([remapExpandedElements]); re-applying the remapped list is best-effort.
   */
  private fun applyCompose(
    slots: RefreshSlots,
    scope: ApplyScope,
    softRebuild: Boolean = false,
    update: () -> Unit = {},
  ) {
    val control = viewer.control
    if (control.isDisposed) return
    // Dispose race: the check above runs off-thread, so the widget (or the whole Display at
    // workbench shutdown) may be disposed before/while we schedule — both throw SWTException.
    // A dispose at any point here just means there is nothing left to paint.
    val display = try {
      control.display
    } catch (@Suppress("SwallowedException") e: SWTException) {
      return
    }
    try {
      display.asyncExec {
        if (control.isDisposed) return@asyncExec
        // A stale generation, or an apply of a superseded current-branch (CI-04) or queries run, paints nothing.
        if (!slots.acceptsApply(refreshState::isCurrent, scope)) return@asyncExec
        update()
        val input = coordinator.compose(slots, viewState.mode)
        val oldInput = (viewer.input as? List<*>).orEmpty().filterIsInstance<SidebarNode>()
        if (softRebuild) invalidateReplacedMrs(oldInput, input)
        // ISSUE-01 F10: discussion keys under query roots this compose replaced or removed are orphaned; roots the
        // memo kept are the same instances and keep theirs. Checked on every compose, not only a settings change:
        // whichever apply runs first after a settings change swapped the slot performs the swap in the tree.
        DiscussionGenerationRegistry.clearLatestFor(staleQueryRootSectionIds(oldInput, input))
        // CI-06 §4.6 R1-1 / R2-2: a rebuilt downstream node takes over its LOADED predecessor's children
        // (same pipeline id, same connection tags) BEFORE the swap, so it neither flashes "Loading…" nor
        // collapses what is expanded inside it; loads the rebuild abandoned are cancelled.
        downstreamLoader.transfer(oldInput, input)
        // Pre-order capture: restoring in the same order expands parents first, so nested
        // entries' widgets exist (materialized by the parent's expansion) when their turn
        // comes. Programmatic expansion fires no treeExpanded events — no spurious loads.
        val expanded = viewer.expandedElements.toList()
        viewer.input = input
        remapExpandedElements(expanded, input, oldInput).forEach { viewer.setExpandedState(it, true) }
        // A rebuilt MR node restored as expanded has no children yet, and programmatic expansion
        // fires no treeExpanded: load them here (as onModeChanged does), or it shows "Loading…" forever.
        viewer.expandedElements
          .filterIsInstance<MergeRequestNode>()
          .filter { it.loadedChildren == null }
          .forEach { loadMrChildren(it) }
        // Same for a downstream node restored as expanded without a carried-over state (CI-06 §4.6).
        downstreamLoader.loadAll(viewer.expandedElements.toList())
      }
    } catch (@Suppress("SwallowedException") e: SWTException) {
      // Display disposed between acquisition and scheduling — nothing to paint.
    }
  }

  /**
   * CI-04 (design §2.4, F4): a soft refresh that rebuilt current-branch MR nodes leaves their
   * cached diff versions and connection tags, and the registry keys of their discussion sections,
   * describing nodes that are gone: drop them, so the new node re-fetches and an in-flight
   * discussion load of the old subtree reports Superseded. UI thread only.
   */
  private fun invalidateReplacedMrs(oldInput: List<SidebarNode>, newInput: List<SidebarNode>) {
    val oldSection = oldInput.filterIsInstance<CurrentBranchSectionNode>().firstOrNull() ?: return
    val newSection = newInput.filterIsInstance<CurrentBranchSectionNode>().firstOrNull() ?: return
    val stale = staleMrKeys(oldSection.children, newSection.children)
    stale.mrCacheEntries.forEach {
      mrVersionCache.remove(it)
      mrConnectionTagsCache.remove(it)
    }
    DiscussionGenerationRegistry.clearLatestFor(stale.discussionNodeIds)
  }

  /**
   * Mode changed: re-compose the current generation's slots with the new mode — never a
   * re-fetch (R8: a Pending slot keeps its Loading placeholder; only a never-fetched view,
   * `currentSlots == null`, starts its first refresh). The query roots rebuild by
   * themselves (the coordinator keys them on the mode); the current-branch section node is
   * mode-independent and reused as-is, so its MR node may hold children composed for the
   * old mode — clear them and re-trigger the still-expanded ones, which re-compose from
   * [mrVersionCache] without re-fetching.
   */
  private fun onModeChanged() {
    if (!::viewer.isInitialized) return
    val control = viewer.control
    if (control.isDisposed) return
    control.display.asyncExec {
      if (control.isDisposed) return@asyncExec
      val slots = currentSlots
      if (slots == null) {
        refreshGeneration(initial = true) // Nothing fetched yet — the fetch will compose with the new mode.
        return@asyncExec
      }
      (viewer.input as? List<*>)
        ?.filterIsInstance<CurrentBranchSectionNode>()
        ?.flatMap { it.children }
        ?.filterIsInstance<MergeRequestNode>()
        ?.forEach { it.loadedChildren = null }
      applyCompose(slots, ApplyScope.Generation)
      // Expanded MR nodes whose children were just cleared would otherwise show "Loading…"
      // forever (programmatic expansion restore fires no treeExpanded event). Query-root MR
      // nodes still in flight are deduped by loadMrChildren's in-flight guard.
      viewer.expandedElements
        .filterIsInstance<MergeRequestNode>()
        .filter { it.loadedChildren == null }
        .forEach { loadMrChildren(it) }
      // Downstream nodes need no call here: applyCompose's own UI block loads the expanded ones after
      // the compose and the expansion restore, which is the only point where the new tree is in place.
    }
  }

  /**
   * UI thread (tree-expansion listener). Lazily populates [node] with an "Overview" node
   * plus the changed files of the MR's latest diff version: straight from [mrVersionCache]
   * when a full refresh or an earlier expansion already fetched it, otherwise via a fetch
   * on the shared IO scope. Applies are deferred with `asyncExec` even on the cache-hit
   * path so the tree is never mutated re-entrantly from inside the expand event.
   */
  private fun loadMrChildren(node: MergeRequestNode) {
    val cacheKey = mrCacheKey(node.mr.webUrl, node.mr.projectId, node.mr.iid)
    val control = viewer.control
    if (control.isDisposed) return
    if (mrVersionCache.containsKey(cacheKey)) {
      // Read, never re-captured: capturing here would run on the UI thread (see
      // mrConnectionTagsCache). Validated all the same: the web-URL key already makes a
      // cross-instance hit impossible, but applying the same membership check as the fetch
      // path keeps this branch self-evidently unable to reuse tags for a foreign MR. A cache
      // entry without stored tags — or one failing the check — yields (null, null), which
      // buildMrChildren already renders as "no Discussions section" rather than a wrong one.
      val tags = validatedCacheTags(mrConnectionTagsCache[cacheKey], node.mr.webUrl)
      control.display.asyncExec {
        if (control.isDisposed) return@asyncExec
        applyMrChildren(node, Result.success(mrVersionCache[cacheKey]), tags.first, tags.second)
      }
      return
    }
    if (!mrLoadsInFlight.add(node)) return
    val generation = refreshState.currentGeneration()
    coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch (see refresh()).
      try {
        // ONE snapshot for the whole fetch, captured BEFORE it: the version fetch is pinned to
        // this connection and the tags below are derived from the same snapshot, so the node can
        // never be stamped with a connection its data did not come from (a capture after the
        // fetch would read whatever is configured THEN, hiding the very mismatch the connection
        // gate compares against). A failed capture (settings mid-change, credential read error)
        // yields null: the fetch falls back to the per-request capture inside the client, and
        // the tags stay (null, null) — rendered as "no Discussions section", exactly as before.
        val snapshot = runCatching { apiClient.captureConnection() }.getOrNull()
        val versionResult = runCatching {
          // REST accepts a numeric project id directly, so no URL encoding is needed.
          mergeRequestService.getLatestMrVersion(node.mr.projectId.toString(), node.mr.iid, snapshot)
        }
        // Logged here so the error message the node renders has a matching Error Log entry.
        versionResult.exceptionOrNull()?.let {
          logger.error("Failed to load changed files for merge request !${node.mr.iid}.", it)
        }
        // Reduced to the two non-secret fields right here, off the UI thread: the snapshot itself
        // holds the token and must never be carried into the UI block below.
        val tags = sourceTagsFor(snapshot, node.mr.webUrl)
        if (!control.isDisposed) {
          control.display.asyncExec {
            if (control.isDisposed) return@asyncExec
            mrLoadsInFlight.remove(node)
            // A newer full refresh replaced the tree (and this node) while we fetched:
            // drop the result rather than poisoning the fresh mrVersionCache with it.
            if (!refreshState.isCurrent(generation)) return@asyncExec
            versionResult.onSuccess { version -> mrVersionCache[cacheKey] = version }
            // Stored under the same condition as the version itself, so the two can never
            // disagree: a failed fetch caches neither, and a later expansion re-fetches both.
            if (versionResult.isSuccess) mrConnectionTagsCache[cacheKey] = tags
            applyMrChildren(node, versionResult, tags.first, tags.second)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to load changed files for merge request !${node.mr.iid}.", e)
      }
    }
  }

  /**
   * UI thread. Composes with the mode read here — at apply time — so a mode toggle that
   * raced the fetch never paints children built for a stale mode (same rule as
   * [applyCompose]). A failed [versionResult] is applied but not cached, so a later full
   * refresh (which rebuilds the node) retries the fetch.
   *
   * [sourceInstanceUrl]/[sourceAuthFingerprint] are the non-secret tags of the connection the
   * children were actually fetched over (the pinned snapshot in [loadMrChildren]); they are
   * stamped on the node's
   * [DiscussionsSectionNode] so a later write can refuse to send when instance OR account has
   * changed underneath it. Both `null` (capture failed) means no Discussions section at all,
   * which is deliberate — see `SidebarViewModel.buildMrChildren`.
   */
  private fun applyMrChildren(
    node: MergeRequestNode,
    versionResult: Result<GitLabMrVersion?>,
    sourceInstanceUrl: String?,
    sourceAuthFingerprint: String?,
  ) {
    node.loadedChildren =
      viewModel.buildMrChildren(node.url, versionResult, viewState.mode, node.mr, sourceInstanceUrl, sourceAuthFingerprint)
    viewer.refresh(node)
    viewer.expandToLevel(node, 1)
  }

  /**
   * UI thread only. Runs [block] on the SWT UI thread, dropping it when the viewer's control (or
   * the whole Display, at workbench shutdown) is disposed — a disposed widget must never be
   * touched, and a dropped block simply means there is no tree left to paint into. Total by
   * construction: both the scheduling and the block itself are wrapped by [guarded], so nothing
   * thrown here can escape into [DiscussionsLoader].
   */
  private fun runOnDiscussionsUiThread(block: () -> Unit) {
    guarded("runOnUi", Unit) {
      val control = viewer.control
      if (control.isDisposed) return@guarded
      control.display.asyncExec {
        if (control.isDisposed) return@asyncExec
        guarded("uiStep", Unit) { block() }
      }
    }
  }

  /**
   * Runs [block], returning [fallback] if it throws. The audit line names the injected step and
   * carries `exceptionType=<class name>` only — never the exception object, the token, the auth
   * fingerprint, or any note text.
   */
  private fun <T> guarded(operation: String, fallback: T, block: () -> T): T =
    try {
      block()
    } catch (e: Exception) {
      logger.error("Discussions UI step failed: operation=$operation exceptionType=${e.javaClass.name}")
      fallback
    }

  /**
   * UI thread only (it reads [viewer]'s current input, which is UI-thread-confined). **Every**
   * [DiscussionsSectionNode] currently in the tree for the given merge request on the given
   * connection, in tree order; empty when the tree holds no such section — e.g. the sidebar was
   * refreshed, the MR node was never expanded, or the connection changed since the node was built.
   *
   * Plural on purpose: one merge request can appear under BOTH "Merge requests assigned to me"
   * and "For current branch", so the (instance, account, project, iid) tuple identifies a merge
   * request but NOT a single node — see [DiscussionsSectionNode.nodeId]. The post-write re-fetch
   * must refresh all of them, because it cannot tell which one the user is looking at, and
   * refreshing only one would let the launcher's Ambiguous message claim a thread was reloaded
   * when it was not.
   *
   * Matching is delegated to the pure [selectDiscussionsSections] so it stays reachable from the
   * headless tests.
   */
  internal fun resolveDiscussionsSections(
    instanceUrl: String,
    authFingerprint: String,
    projectId: Long,
    mrIid: Long,
  ): List<DiscussionsSectionNode> {
    if (!::viewer.isInitialized || viewer.control.isDisposed) return emptyList()
    val sections = (viewer.input as? List<*>)
      .orEmpty()
      .filterIsInstance<SidebarNode>()
      .flatMap(::collectDiscussionsSections)
    return selectDiscussionsSections(sections, instanceUrl, authFingerprint, projectId, mrIid)
  }

  /**
   * UI thread only. Re-fetches [section] after a discussion write and reports the load's outcome
   * to [onOutcome] (design FR-10 / AC-11).
   *
   * `force = true` is the requirement, not an optimization: after a successful write the section
   * is already `LOADED`, so a non-forced load would return [LoadOutcome.Skipped] without fetching
   * anything and leave the user looking at a stale thread — and, on the ambiguous-outcome path,
   * would report the thread as not reloaded even though it could have been.
   */
  internal fun reloadDiscussions(section: DiscussionsSectionNode, onOutcome: (LoadOutcome) -> Unit) {
    discussionsLoader.loadDiscussions(section, force = true, onOutcome)
  }

  override fun setFocus() {
    if (!viewer.control.isDisposed) viewer.control.setFocus()
  }

  override fun dispose() {
    if (queriesLazy.isInitialized()) queries.dispose()
    viewState.removeListener(modeListener)
    if (currentBranchBindingLazy.isInitialized()) currentBranchBinding.detach()
    if (downstreamLoaderLazy.isInitialized()) downstreamLoader.cancelAll()
    super.dispose()
  }
}

/**
 * Remaps a captured expanded-elements list onto a freshly composed [newInput] (R9): the
 * top-level node INSTANCES change per compose — [QueryRootNode]s and the
 * [CurrentBranchSectionNode] are rebuilt — so they are matched by stable key instead: a
 * query root by its [QueryRootNode.queryId] (two roots may share a label; a rename keeps the id), the
 * current-branch section by its type. Everything else (the
 * coordinator's identity-preserved inner nodes) passes through unchanged. Best-effort:
 * an unmatched stale element passes through too and is simply not found by the viewer.
 *
 * CI-04: a soft refresh can also rebuild the nodes INSIDE the current-branch section, so an
 * element that was inside [oldInput]'s section is remapped into [newInput]'s section by its
 * stable key ([remapInSection]). Only those: an MR expanded under a query root keeps its own
 * identity rather than being mapped onto the current-branch copy of the same MR.
 */
/** What a double-click on a sidebar node does. */
internal sealed interface SidebarDoubleClick {
  /** A changed file opens its base/head diff (REVIEW-01). */
  class OpenDiff(val node: ChangedFileNode) : SidebarDoubleClick

  /** A node with an activation URL opens it in the external browser. */
  class OpenUrl(val url: String) : SidebarDoubleClick

  object None : SidebarDoubleClick
}

/** Routes a double-click on [node]: changed files to the diff, other nodes to their URL if any. */
internal fun sidebarDoubleClick(node: SidebarNode?): SidebarDoubleClick {
  if (node is ChangedFileNode) return SidebarDoubleClick.OpenDiff(node)
  val url = node?.activationUrl ?: return SidebarDoubleClick.None
  return SidebarDoubleClick.OpenUrl(url)
}

internal fun remapExpandedElements(
  expanded: List<Any>,
  newInput: List<SidebarNode>,
  oldInput: List<SidebarNode> = emptyList(),
): List<Any> {
  val oldSection = oldInput.firstOrNull { it is CurrentBranchSectionNode }
  val newSection = newInput.firstOrNull { it is CurrentBranchSectionNode }
  return expanded.map { element ->
    when {
      element is QueryRootNode ->
        newInput.firstOrNull { it is QueryRootNode && it.queryId == element.queryId } ?: element
      element is CurrentBranchSectionNode -> newSection ?: element
      oldSection != null && newSection != null -> remapInSection(element, oldSection, newSection) ?: element
      else -> element
    }
  }
}

/**
 * The two non-secret connection tags to stamp on a merge request node's children, or
 * `(null, null)` — rendered as "no Discussions section" — when there is nothing truthful to
 * stamp: no snapshot was captured, or the merge request does not belong to the captured
 * instance. The MR came from an earlier list fetch that may have run under a DIFFERENT
 * connection, and tagging foreign data with the live connection would make the connection gate
 * pass and send the MR's identifiers to the wrong instance or account.
 */
private fun sourceTagsFor(snapshot: ConnectionSnapshot?, mrWebUrl: String?): Pair<String?, String?> =
  if (snapshot != null && mrBelongsToInstance(mrWebUrl, snapshot.instanceUrl)) {
    snapshot.instanceUrl to snapshot.authFingerprint
  } else {
    null to null
  }

/**
 * The connection tags to reuse on [GitLabSidebarView.loadMrChildren]'s cache-hit path, or
 * `(null, null)` — rendered as "no Discussions section" — when nothing is cached or the cached
 * instance does not own [mrWebUrl] under [mrBelongsToInstance]. The web-URL cache key already
 * makes a cross-instance hit impossible; this is the same membership check the fetch path
 * applies via [sourceTagsFor], repeated here as defence in depth so the hit branch is safe on
 * its own terms rather than safe only by an argument about the key.
 */
private fun validatedCacheTags(cached: Pair<String?, String?>?, mrWebUrl: String?): Pair<String?, String?> {
  val cachedInstanceUrl = cached?.first ?: return null to null
  return if (mrBelongsToInstance(mrWebUrl, cachedInstanceUrl)) cached else null to null
}

/**
 * The key under which [GitLabSidebarView]'s `mrVersionCache` and `mrConnectionTagsCache` store one
 * merge request's entries: the MR's [webUrl] when usable (globally unique, so two instances can
 * never share a key), otherwise the numeric fallback `"mr-id:<projectId>/<mrIid>"`.
 * [com.gitlab.eclipse.api.model.GitLabMergeRequest] declares `webUrl` non-null, but Gson builds it
 * through `Unsafe` and skips the constructor, so a response without `web_url` leaves it null (or
 * blank) at runtime regardless — and keying every such MR by that one degenerate value would make
 * them all share a single entry, rendering one MR's cached changed files under another. The
 * fallback restores a per-MR key, and cannot collide with a URL key: a real web URL always begins
 * with a scheme, which `mr-id:<digits>/...` never does.
 *
 * The fallback is deliberately NOT instance-qualified — there is nothing truthful to qualify it
 * with — so a cross-instance collision on it remains possible in principle, exactly as under the
 * numeric key this fallback restores. That only ever affects the version cache: the tags path is
 * separately protected, because [mrBelongsToInstance] rejects a null or blank web URL, so a
 * fallback-keyed MR always gets `(null, null)` tags and renders WITHOUT a Discussions section. Do
 * not "simplify" the fallback into the sole key on that argument — the URL key is what keeps one
 * instance's diff versions out of another instance's nodes. Pure and SWT-free so the headless
 * tests can reach it.
 */
internal fun mrCacheKey(webUrl: String?, projectId: Long, mrIid: Long): String =
  if (webUrl.isNullOrBlank()) "mr-id:$projectId/$mrIid" else webUrl

/**
 * Whether a merge request whose web URL is [mrWebUrl] belongs to the instance at [instanceUrl]:
 * the web URL must start with the normalized instance URL followed by a `/` boundary, so
 * `https://gitlab.example.com` never matches `https://gitlab.example.com.attacker.test/...`.
 * A null or blank [mrWebUrl] fails the check ([com.gitlab.eclipse.api.model.GitLabMergeRequest]
 * declares `webUrl` non-null, but Gson builds it through `Unsafe` and a key missing from the
 * response leaves it null regardless). Pure and SWT-free so the headless tests can reach it.
 */
internal fun mrBelongsToInstance(mrWebUrl: String?, instanceUrl: String): Boolean {
  if (mrWebUrl.isNullOrBlank()) return false
  val normalized = normalizeInstanceUrl(instanceUrl)
  if (normalized.isBlank()) return false
  return mrWebUrl.startsWith("$normalized/")
}

/**
 * Every [DiscussionsSectionNode] at or under [node], depth-first. Recursion stops at a section
 * itself (a section's own children are threads, never further sections), and reading
 * [SidebarNode.children] of a not-yet-loaded node is safe: it yields that node's stable
 * "Loading…" placeholder rather than triggering a fetch. UI thread only, like its caller.
 */
private fun collectDiscussionsSections(node: SidebarNode): List<DiscussionsSectionNode> =
  if (node is DiscussionsSectionNode) listOf(node) else node.children.flatMap(::collectDiscussionsSections)

/**
 * Every section in [sections] that belongs to the given merge request on the given connection,
 * in the order given (= tree order at the call site).
 *
 * All four values must match. The instance URLs are compared through [normalizeInstanceUrl] on
 * both sides — the same normalization the connection gate uses, so a trailing-slash difference
 * cannot cause a miss — and [instanceUrl] is normalized once rather than per candidate. The
 * fingerprint is compared exactly: the same URL with a different credential is a different
 * account, and a URL-only check would let a write address the wrong one.
 *
 * Pure and SWT-free so the headless tests can reach it.
 */
internal fun selectDiscussionsSections(
  sections: List<DiscussionsSectionNode>,
  instanceUrl: String,
  authFingerprint: String,
  projectId: Long,
  mrIid: Long,
): List<DiscussionsSectionNode> {
  val normalizedInstanceUrl = normalizeInstanceUrl(instanceUrl)
  return sections.filter {
    normalizeInstanceUrl(it.sourceInstanceUrl) == normalizedInstanceUrl &&
      it.sourceAuthFingerprint == authFingerprint &&
      it.projectId == projectId &&
      it.mrIid == mrIid
  }
}

/**
 * The [DiscussionsSectionNode.nodeId]s of every section at or under [nodes]: the set a full
 * refresh hands to [DiscussionGenerationRegistry.clearLatestFor], so it invalidates exactly the
 * keys owned by the tree that refresh is about to replace — never another window's. Walking
 * [SidebarNode.children] is a pure read on every node type (an unloaded node yields its stable
 * "Loading…" placeholder, never a fetch). Pure and SWT-free so the headless tests can reach it.
 */
internal fun collectDiscussionSectionNodeIds(nodes: List<SidebarNode>): Set<Long> =
  nodes.flatMap(::collectDiscussionsSections).mapTo(mutableSetOf()) { it.nodeId }
