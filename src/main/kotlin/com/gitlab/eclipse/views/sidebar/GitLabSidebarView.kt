package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.IssueService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.inject.lazyService
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
import java.net.URI

/**
 * GitLab sidebar: a [TreeViewer] over the two query roots produced by [SidebarViewModel]
 * ("Issues assigned to me" / "Merge requests assigned to me").
 *
 * Threading: fetches run on the shared IO [CoroutineScope]; results hop to the SWT UI
 * thread via `asyncExec` and are dropped when stale ([ViewRefreshState] generation) or
 * when the widget is disposed. The last fetch results are cached (UI thread only) so a
 * [SidebarViewMode] toggle re-composes the tree without re-fetching.
 */
class GitLabSidebarView : ViewPart() {
  private val logger = logger<GitLabSidebarView>()
  private val issueService by lazyService<IssueService>()
  private val mergeRequestService by lazyService<MergeRequestService>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val viewState by lazyService<SidebarViewState>()

  private val viewModel = SidebarViewModel()
  private val refreshState = ViewRefreshState()

  private lateinit var viewer: TreeViewer

  @Volatile private var fetchJob: Job? = null

  // Last fetch results, read/written on the UI thread only (inside asyncExec / listeners),
  // so a mode toggle can re-compose without re-fetching and without torn reads.
  private var cachedIssues: Result<List<GitLabIssue>>? = null
  private var cachedMrs: Result<List<GitLabMergeRequest>>? = null

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
    fetchJob?.cancel()
    fetchJob = coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch or every other
      // consumer of the scope loses its coroutines.
      try {
        supervisorScope {
          val issues = async { runCatching { issueService.getIssuesAssignedToMe() } }
          val mrs = async { runCatching { mergeRequestService.getMergeRequestsAssignedToMe() } }
          val issuesResult = issues.await()
          val mrsResult = mrs.await()
          val control = viewer.control
          if (!control.isDisposed) {
            control.display.asyncExec { applyResults(generation, issuesResult, mrsResult) }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to refresh the GitLab sidebar.", e)
      }
    }
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
  ) {
    if (!refreshState.isCurrent(generation)) return
    if (viewer.control.isDisposed) return
    cachedIssues = issuesResult
    cachedMrs = mrsResult
    viewer.input = viewModel.buildRoots(issuesResult, mrsResult, viewState.mode)
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
      if (issues == null || mrs == null) {
        refresh() // Nothing fetched yet — the fetch will compose with the new mode.
        return@asyncExec
      }
      viewer.input = viewModel.buildRoots(issues, mrs, viewState.mode)
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
