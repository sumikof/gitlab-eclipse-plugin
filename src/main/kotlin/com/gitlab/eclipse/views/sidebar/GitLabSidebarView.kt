package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.IssueService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.issues.ViewRefreshState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.eclipse.jface.action.MenuManager
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.jface.viewers.ITreeViewerListener
import org.eclipse.jface.viewers.TreeExpansionEvent
import org.eclipse.jface.viewers.TreeViewer
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.part.ViewPart
import java.io.File
import java.net.URI

/** Shown in the "For current branch" section when no single repository can be resolved. */
private const val SELECT_REPOSITORY_MESSAGE = "Select a repository"

/**
 * GitLab sidebar: a [TreeViewer] over the two query roots produced by [SidebarViewModel]
 * ("Issues assigned to me" / "Merge requests assigned to me") plus the "For current
 * branch" section (open MR for the checked-out branch and the issues it would close).
 *
 * Threading: fetches run on the shared IO [CoroutineScope]; results hop to the SWT UI
 * thread via `asyncExec` and are dropped when stale ([ViewRefreshState] generation) or
 * when the widget is disposed. The last fetch results are cached (UI thread only) so a
 * [SidebarViewMode] toggle re-composes the tree without re-fetching.
 *
 * MR nodes expand lazily ([loadMrChildren]): first expansion fetches the MR's latest diff
 * version and renders an "Overview" node plus its changed files (flat in LIST mode,
 * folder hierarchy in TREE mode), under the same threading discipline.
 */
class GitLabSidebarView : ViewPart() {
  companion object {
    /** Must match the view id declared in plugin.xml. */
    const val VIEW_ID = "com.gitlab.eclipse.views.GitLabSidebarView"
  }

  private val logger = logger<GitLabSidebarView>()
  private val issueService by lazyService<IssueService>()
  private val mergeRequestService by lazyService<MergeRequestService>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val viewState by lazyService<SidebarViewState>()

  private val viewModel = SidebarViewModel()
  private val refreshState = ViewRefreshState()

  // Deliberately not Koin singles (see RepositoryContextResolver KDoc); lazy so that
  // their `service()` default arguments resolve only once the workbench is up.
  private val repositoryContextResolver by lazy { RepositoryContextResolver() }
  private val currentBranchGitReader by lazy { CurrentBranchGitReader() }
  private val currentBranchMrLookup by lazy { CurrentBranchMrLookup() }

  private lateinit var viewer: TreeViewer

  @Volatile private var fetchJob: Job? = null

  // Last fetch results, read/written on the UI thread only (inside asyncExec / listeners),
  // so a mode toggle can re-compose without re-fetching and without torn reads.
  private var cachedIssues: Result<List<GitLabIssue>>? = null
  private var cachedMrs: Result<List<GitLabMergeRequest>>? = null

  // Mode-independent, so a mode toggle reuses the node as-is (no re-fetch, no re-build).
  private var cachedCurrentBranchSection: SidebarNode? = null

  // Latest MR diff version per (projectId, iid), UI thread only. Survives mode toggles —
  // re-expanding an MR re-composes its children in the new mode without re-fetching — and
  // is cleared when a full refresh applies, so refreshed sidebars pick up new diff versions.
  private val mrVersionCache = mutableMapOf<Pair<Long, Long>, GitLabMrVersion?>()

  // MR nodes with a version fetch in flight (UI thread only; identity-keyed since
  // MergeRequestNode does not override equals), so collapse/re-expand while a fetch is
  // running does not start a duplicate fetch.
  private val mrLoadsInFlight = mutableSetOf<MergeRequestNode>()

  // Stored so dispose() can remove this exact instance from the shared SidebarViewState.
  private val modeListener: () -> Unit = { onModeChanged() }

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
      val url = node?.activationUrl ?: return@addDoubleClickListener
      openInBrowser(url)
    }

    viewer.addTreeListener(
      object : ITreeViewerListener {
        override fun treeExpanded(event: TreeExpansionEvent) {
          val node = event.element as? MergeRequestNode ?: return
          if (node.loadedChildren == null) loadMrChildren(node)
        }

        override fun treeCollapsed(event: TreeExpansionEvent) = Unit
      },
    )

    viewState.addListener(modeListener)
    refresh()
  }

  fun refresh() {
    val generation = refreshState.begin()
    // Captured here — refresh() always runs on the UI thread (createPartControl, the
    // refresh handler, onModeChanged's asyncExec) — because the active-editor read goes
    // through the workbench, which silently yields null off the UI thread. Only this cheap
    // workbench read stays on the UI thread; the blocking JGit resolution runs inside the
    // fetch coroutine below. Non-interactive by design: never a picker during an
    // auto-refresh; an ambiguous workspace resolves to null → "Select a repository".
    val activeEditorFile = repositoryContextResolver.activeEditorFile()
    fetchJob?.cancel()
    fetchJob = coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch or every other
      // consumer of the scope loses its coroutines.
      try {
        supervisorScope {
          val issues = async { runCatching { issueService.getIssuesAssignedToMe() } }
          val mrs = async { runCatching { mergeRequestService.getMergeRequestsAssignedToMe() } }
          val currentBranch = async {
            // JGit enumeration/resolution (IO), then the branch read + REST lookup. A null
            // context means "no repository resolved" and flows through as a null Result.
            repositoryContextResolver.resolveNonInteractive(activeEditorFile)?.let { context ->
              runCatching { fetchCurrentBranchInfo(context) }
            }
          }
          val issuesResult = issues.await()
          val mrsResult = mrs.await()
          val currentBranchResult = currentBranch.await()
          // Log per-root failures here so the "see the Error Log" message the tree renders
          // actually has a matching Error Log entry; the failed Results still flow to
          // buildRoots so the other root stays populated.
          issuesResult.exceptionOrNull()?.let {
            logger.error("Failed to load issues assigned to you.", it)
          }
          mrsResult.exceptionOrNull()?.let {
            logger.error("Failed to load merge requests assigned to you.", it)
          }
          currentBranchResult?.exceptionOrNull()?.let {
            logger.error("Failed to load current-branch merge request.", it)
          }
          val control = viewer.control
          if (!control.isDisposed) {
            control.display.asyncExec {
              applyResults(generation, issuesResult, mrsResult, currentBranchResult)
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to refresh the GitLab sidebar.", e)
      }
    }
  }

  /** Runs in the fetch coroutine: local JGit read, then the REST lookup. */
  private fun fetchCurrentBranchInfo(context: RepositoryContext): CurrentBranchInfo {
    val branch = currentBranchGitReader.read(File(context.gitDir))
    return currentBranchMrLookup.lookup(context, branch)
  }

  /**
   * UI thread. Composes the roots here — with the mode read on the UI thread — rather
   * than in the fetch coroutine, so a mode toggle that races an in-flight fetch can
   * never paint nodes composed for a stale mode. `buildRoots` is pure list mapping.
   */
  private fun applyResults(
    generation: Long,
    issuesResult: Result<List<GitLabIssue>>,
    mrsResult: Result<List<GitLabMergeRequest>>,
    currentBranchResult: Result<CurrentBranchInfo>?,
  ) {
    if (!refreshState.isCurrent(generation)) return
    if (viewer.control.isDisposed) return
    // A null result = no repository could be resolved non-interactively (none, or several
    // and no active editor to disambiguate) — distinct from a resolved repository with no
    // MR, which buildCurrentBranchSection renders as "No merge request found".
    val currentBranchSection =
      currentBranchResult?.let(viewModel::buildCurrentBranchSection)
        ?: CurrentBranchSectionNode(listOf(MessageNode(SELECT_REPOSITORY_MESSAGE)))
    cachedIssues = issuesResult
    cachedMrs = mrsResult
    cachedCurrentBranchSection = currentBranchSection
    // A full refresh rebuilds every MR node (loadedChildren = null again); drop the cached
    // versions too so the next expansion re-fetches and picks up newly pushed diffs.
    mrVersionCache.clear()
    viewer.input =
      viewModel.buildRoots(issuesResult, mrsResult, viewState.mode) + currentBranchSection
  }

  /** Mode changed: re-compose from the cached results without re-fetching. */
  private fun onModeChanged() {
    if (!::viewer.isInitialized) return
    val control = viewer.control
    if (control.isDisposed) return
    control.display.asyncExec {
      if (control.isDisposed) return@asyncExec
      val issues = cachedIssues
      val mrs = cachedMrs
      val currentBranchSection = cachedCurrentBranchSection
      if (issues == null || mrs == null || currentBranchSection == null) {
        refresh() // Nothing fetched yet — the fetch will compose with the new mode.
        return@asyncExec
      }
      // The current-branch section itself is mode-independent and reused as-is, but its MR
      // node may hold children composed for the old mode: clear them so the next expansion
      // re-composes from mrVersionCache (no re-fetch). Query-root MR nodes need nothing —
      // buildRoots creates fresh instances, and their expansion also hits the cache.
      currentBranchSection.children
        .filterIsInstance<MergeRequestNode>()
        .forEach { it.loadedChildren = null }
      viewer.input = viewModel.buildRoots(issues, mrs, viewState.mode) + currentBranchSection
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
    val cacheKey = node.mr.projectId to node.mr.iid
    val control = viewer.control
    if (control.isDisposed) return
    if (mrVersionCache.containsKey(cacheKey)) {
      control.display.asyncExec {
        if (control.isDisposed) return@asyncExec
        applyMrChildren(node, Result.success(mrVersionCache[cacheKey]))
      }
      return
    }
    if (!mrLoadsInFlight.add(node)) return
    val generation = refreshState.currentGeneration()
    coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch (see refresh()).
      try {
        val versionResult = runCatching {
          // REST accepts a numeric project id directly, so no URL encoding is needed.
          mergeRequestService.getLatestMrVersion(node.mr.projectId.toString(), node.mr.iid)
        }
        // Logged here so the error message the node renders has a matching Error Log entry.
        versionResult.exceptionOrNull()?.let {
          logger.error("Failed to load changed files for merge request !${node.mr.iid}.", it)
        }
        if (!control.isDisposed) {
          control.display.asyncExec {
            if (control.isDisposed) return@asyncExec
            mrLoadsInFlight.remove(node)
            // A newer full refresh replaced the tree (and this node) while we fetched:
            // drop the result rather than poisoning the fresh mrVersionCache with it.
            if (!refreshState.isCurrent(generation)) return@asyncExec
            versionResult.onSuccess { version -> mrVersionCache[cacheKey] = version }
            applyMrChildren(node, versionResult)
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
   * [applyResults]). A failed [versionResult] is applied but not cached, so a later full
   * refresh (which rebuilds the node) retries the fetch.
   */
  private fun applyMrChildren(node: MergeRequestNode, versionResult: Result<GitLabMrVersion?>) {
    node.loadedChildren = viewModel.buildMrChildren(node.url, versionResult, viewState.mode)
    viewer.refresh(node)
    viewer.expandToLevel(node, 1)
  }

  private fun openInBrowser(url: String) {
    try {
      PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(URI.create(url).toURL())
    } catch (e: Exception) {
      logger.error("Failed to open URL: $url", e)
    }
  }

  override fun setFocus() {
    if (!viewer.control.isDisposed) viewer.control.setFocus()
  }

  override fun dispose() {
    viewState.removeListener(modeListener)
    fetchJob?.cancel()
    super.dispose()
  }
}
