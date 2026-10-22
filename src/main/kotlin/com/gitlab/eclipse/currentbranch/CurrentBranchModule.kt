package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.PipelineService
import com.gitlab.eclipse.chat.quickchat.ui.DisplayUiScheduler
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.HeadTagReader
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.utils.debug
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionFetcher
import com.gitlab.eclipse.views.sidebar.autorefresh.CurrentBranchAutoRefresher
import com.gitlab.eclipse.views.sidebar.autorefresh.DisplayAutoRefreshTimer
import com.gitlab.eclipse.views.sidebar.autorefresh.GitHeadProbe
import com.gitlab.eclipse.views.sidebar.autorefresh.JGitRefsEventSource
import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import com.gitlab.eclipse.views.sidebar.autorefresh.autoRefreshHop
import kotlinx.coroutines.CoroutineScope
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.koin.dsl.module
import java.io.File

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The current-branch state registry (status-bar design §4.1). Activated in the post-start UI turn and disposed
 * in the UI shutdown turn (`GitLabEclipseStartup`); the trim control may create entries first (R3).
 */
val currentBranchModule = module {
  single<CurrentBranchStateService> {
    val scope = get<CoroutineScope>()
    val apiClient = get<GitLabApiClient>()
    val mergeRequestService = get<MergeRequestService>()
    val log = logger<CurrentBranchStateService>()
    val warn: (String) -> Unit = { message -> log.warn(message) }
    val filter = SharedActivationFilter(displayActivationFilter { PlatformUI.getWorkbench().display }, warn)
    CurrentBranchStateService(
      createState = { window -> productionWindowState(window, scope, apiClient, mergeRequestService, filter) },
      workbench = PlatformUI::getWorkbench,
      isWindowOpen = ::isWindowOpen,
      onDispose = filter::dispose,
      log = warn,
    )
  }
}

/**
 * One window's entry wired to the platform, as the sidebar wired its CI-04 refresher (`GitLabSidebarView`
 * `startAutoRefresh`), but with this window's activity and editor: Display timer and hop, JGit refs events and
 * HEAD probe, a fetcher of its own (its own failure-streak memory), and a part listener on this window's part
 * service (R20). Created on the UI thread (both [CurrentBranchStateService.ensureWindow] callers are).
 */
private fun productionWindowState(
  window: IWorkbenchWindow,
  scope: CoroutineScope,
  apiClient: GitLabApiClient,
  mergeRequestService: MergeRequestService,
  filter: SharedActivationFilter,
): WindowCurrentBranchState {
  val log = logger<WindowCurrentBranchState>()
  val warn: (String) -> Unit = { log.warn(it) }
  val display = window.workbench.display
  val scheduler = DisplayUiScheduler(display)
  val hop = autoRefreshHop(scheduler, warn)
  val resolver = RepositoryContextResolver()
  val reader = CurrentBranchGitReader()
  val fetcher = CurrentBranchSectionFetcher(
    resolver,
    reader,
    CurrentBranchMrLookup(),
    PipelineService(apiClient),
    JobService(apiClient),
    BridgeService(apiClient),
    mergeRequestService,
    HeadTagReader(),
    apiClient,
    // Full refresh: the Error Log entries of the sidebar's full refresh, unchanged (the fetcher writes no path).
    errorLog = { message, e -> log.error(message, e) },
    warnLog = warn,
  )
  return WindowCurrentBranchState(
    fetch = CurrentBranchFetch { file, previous, previousGitDir, soft, onHead, apply ->
      fetcher.fetch(file, previous, previousGitDir, soft, onHead, apply)
    },
    editorFile = { WindowEditorFileReader.activeEditorFile(window) },
    scope = scope,
    hop = hop,
    createRefresher = { port ->
      lateinit var refresher: CurrentBranchAutoRefresher
      refresher = CurrentBranchAutoRefresher(
        clock = { System.nanoTime() / NANOS_PER_MILLI },
        timer = DisplayAutoRefreshTimer(scheduler, warn),
        activity = WindowActivitySource.of(window, display, filter, warn),
        refs = JGitRefsEventSource({ refresher.trackedGitDir() }, hop, warn),
        probe = GitHeadProbe(scope, reader, hop),
        port = port,
        log = warn,
        debugLog = { log.debug(it) },
      )
      refresher
    },
    installEditorListener = { onActivated -> editorActivationListener(window, onActivated) },
    canonical = { File(it).canonicalPath },
    log = warn,
  )
}

/**
 * T3b: a window whose shell is gone (or not created) or a closing workbench gets no entry: a trim control rebuilt
 * while its window closes would otherwise create one after `windowClosed`, and nothing would ever dispose it.
 */
private fun isWindowOpen(window: IWorkbenchWindow): Boolean {
  val shell = window.shell ?: return false
  return !shell.isDisposed && !window.workbench.isClosing
}

/** §4.3/R20: an editor of [window] activated (or its input replaced) reports that editor's file. */
private fun editorActivationListener(window: IWorkbenchWindow, onActivated: (File?) -> Unit): Registration {
  val listener = object : IPartListener2 {
    override fun partActivated(partRef: IWorkbenchPartReference) = report(partRef)

    override fun partInputChanged(partRef: IWorkbenchPartReference) = report(partRef)

    private fun report(partRef: IWorkbenchPartReference) {
      if (partRef is IEditorReference) onActivated(WindowEditorFileReader.fileOf(partRef.getEditor(false)))
    }
  }
  val partService = window.partService
  partService.addPartListener(listener)
  return Registration { partService.removePartListener(listener) }
}
