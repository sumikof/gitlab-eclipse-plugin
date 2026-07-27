package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.IssueService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
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
import org.eclipse.jface.viewers.IStructuredSelection
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

  // Stored so dispose() can remove this exact instance from the shared SidebarViewState.
  private val modeListener: () -> Unit = { onModeChanged() }

  override fun createPartControl(parent: Composite) {
    viewer = TreeViewer(parent, SWT.SINGLE or SWT.H_SCROLL or SWT.V_SCROLL)
    viewer.contentProvider = SidebarContentProvider()
    viewer.labelProvider = SidebarLabelProvider()
    viewer.tree.headerVisible = false

    viewer.addDoubleClickListener { event ->
      val node = (event.selection as? IStructuredSelection)?.firstElement as? SidebarNode
      val url = node?.activationUrl ?: return@addDoubleClickListener
      openInBrowser(url)
    }

    viewState.addListener(modeListener)
    refresh()
  }

  fun refresh() {
    val generation = refreshState.begin()
    // Resolved here — refresh() always runs on the UI thread (createPartControl, the
    // refresh handler, onModeChanged's asyncExec) — because RepositoryContextResolver
    // reads the active editor through the workbench, which silently yields null off the
    // UI thread. Non-interactive by design: never a picker during an auto-refresh; an
    // ambiguous workspace resolves to null and renders as "Select a repository".
    val currentBranchContext = repositoryContextResolver.activeOrSingleContext()
    fetchJob?.cancel()
    fetchJob = coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch or every other
      // consumer of the scope loses its coroutines.
      try {
        supervisorScope {
          val issues = async { runCatching { issueService.getIssuesAssignedToMe() } }
          val mrs = async { runCatching { mergeRequestService.getMergeRequestsAssignedToMe() } }
          val currentBranch = currentBranchContext?.let { context ->
            async { runCatching { fetchCurrentBranchInfo(context) } }
          }
          val issuesResult = issues.await()
          val mrsResult = mrs.await()
          val currentBranchResult = currentBranch?.await()
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
    val currentBranchSection = buildCurrentBranchSection(currentBranchResult)
    cachedIssues = issuesResult
    cachedMrs = mrsResult
    cachedCurrentBranchSection = currentBranchSection
    viewer.input =
      viewModel.buildRoots(issuesResult, mrsResult, viewState.mode) + currentBranchSection
  }

  /**
   * `null` result = no repository could be resolved non-interactively (none, or several and
   * no active editor to disambiguate) — distinct from a resolved repository with no MR,
   * which [SidebarViewModel.buildCurrentBranchSection] renders as "No merge request found".
   */
  private fun buildCurrentBranchSection(result: Result<CurrentBranchInfo>?): SidebarNode =
    result?.let(viewModel::buildCurrentBranchSection)
      ?: CurrentBranchSectionNode(listOf(MessageNode(SELECT_REPOSITORY_MESSAGE)))

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
      // The current-branch section is mode-independent: reuse the cached node unchanged.
      viewer.input = viewModel.buildRoots(issues, mrs, viewState.mode) + currentBranchSection
    }
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
