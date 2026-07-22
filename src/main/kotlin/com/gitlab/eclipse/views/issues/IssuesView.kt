@file:Suppress("MagicNumber")

package com.gitlab.eclipse.views.issues

import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.IssueService
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.eclipse.jface.viewers.ArrayContentProvider
import org.eclipse.jface.viewers.ColumnLabelProvider
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.jface.viewers.TableViewer
import org.eclipse.jface.viewers.TableViewerColumn
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.part.ViewPart
import java.net.URI

class IssuesView : ViewPart() {
  private val logger = logger<IssuesView>()
  private val issueService by lazyService<IssueService>()
  private val coroutineScope by lazyService<CoroutineScope>()

  private lateinit var viewer: TableViewer
  private val refreshState = ViewRefreshState()
  private lateinit var presenter: IssueListPresenter

  @Volatile private var fetchJob: Job? = null

  override fun createPartControl(parent: Composite) {
    viewer = TableViewer(parent, SWT.SINGLE or SWT.H_SCROLL or SWT.V_SCROLL or SWT.FULL_SELECTION)
    viewer.contentProvider = ArrayContentProvider.getInstance()
    TableViewerColumn(viewer, SWT.NONE).apply {
      column.text = "Issue"
      column.width = 600
      setLabelProvider(object : ColumnLabelProvider() {
        override fun getText(element: Any?): String {
          val issue = element as GitLabIssue
          val ref = issue.references?.full ?: "#${issue.iid}"
          return "$ref  ${issue.title}"
        }
      })
    }
    viewer.table.headerVisible = false

    viewer.addDoubleClickListener { event ->
      val issue = (event.selection as? IStructuredSelection)?.firstElement as? GitLabIssue ?: return@addDoubleClickListener
      openInBrowser(issue.webUrl)
    }

    presenter = IssueListPresenter(
      state = refreshState,
      isDisposed = { viewer.control.isDisposed },
      applyInput = { applyIssues(it) },
      showError = { showError(it) },
    )

    refresh()
  }

  fun refresh() {
    val generation = refreshState.begin()
    fetchJob?.cancel()
    fetchJob = coroutineScope.launch {
      val result = runCatching { issueService.getIssuesAssignedToMe() }
      val control = viewer.control
      if (control.isDisposed) return@launch
      control.display.asyncExec { presenter.onResult(generation, result) }
    }
  }

  private fun openInBrowser(url: String) {
    try {
      PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(URI.create(url).toURL())
    } catch (e: Exception) {
      logger.error("Failed to open issue URL: $url", e)
    }
  }

  private fun applyIssues(issues: List<GitLabIssue>) {
    if (viewer.control.isDisposed) return
    viewer.input = issues
    setContentDescription(if (issues.isEmpty()) "No issues assigned to you." else "")
  }

  private fun showError(error: Throwable) {
    logger.error("Failed to load issues assigned to you.", error)
    if (viewer.control.isDisposed) return
    viewer.input = emptyList<GitLabIssue>()
    setContentDescription(
      configErrorMessage(error) ?: "Failed to load issues — see the Error Log for details.",
    )
  }

  override fun setFocus() {
    if (!viewer.control.isDisposed) viewer.control.setFocus()
  }

  override fun dispose() {
    fetchJob?.cancel()
    super.dispose()
  }
}

/** The user-safe message to display for a config error, or null to use the generic text. */
internal fun configErrorMessage(error: Throwable): String? =
  (error as? GitLabConfigurationException)?.message
