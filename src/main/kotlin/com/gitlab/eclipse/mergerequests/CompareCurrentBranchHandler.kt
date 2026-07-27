package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.ProjectDetailService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import java.io.File

/**
 * Opens the GitLab compare page between the project's default branch and the current HEAD commit
 * in the external browser (VSCode `gl.compareCurrentBranch` parity, Phase 3 §FR-3).
 */
@Suppress("unused")
class CompareCurrentBranchHandler(
  private val resolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val reader: CurrentBranchGitReader = CurrentBranchGitReader(),
  private val projectDetail: ProjectDetailService = service(),
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<CompareCurrentBranchHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // selectActiveContext must run on the UI thread (it captures the active editor at call
    // time; JGit resolution then runs in the background); a null context is a silent no-op
    // because the resolver already notified the user.
    resolver.selectActiveContext { context ->
      if (context != null) compareCurrentBranch(context)
    }
    logger.info("compareCurrentBranch requested.")
    return null
  }

  private fun compareCurrentBranch(context: RepositoryContext) {
    // The callback fires on the UI thread, and the project fetch is blocking HTTP + JGit
    // I/O — hop to a background coroutine before doing any of it.
    coroutineScope.launch {
      try {
        val branch = reader.read(File(context.gitDir))
        val defaultBranch = projectDetail.getProject(context.projectId).defaultBranch
        val headSha = branch.headSha
        when {
          defaultBranch == null -> NotificationUtils.show("Could not determine the default branch.")
          headSha == null -> NotificationUtils.show("No commit found on the current branch.")
          else -> browser.open(MrUrlBuilder.compareUrl(context.webUrl, defaultBranch, headSha))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to open the branch compare page.", e)
        NotificationUtils.show("GitLab: Could not open the compare page. Check your token and connection.")
      }
    }
  }
}
