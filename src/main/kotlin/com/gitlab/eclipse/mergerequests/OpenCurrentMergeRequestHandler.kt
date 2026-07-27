package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.inject.lazyService
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
 * Opens the current branch's open merge request in the external browser
 * (VSCode `gl.openCurrentMergeRequest` parity, Phase 3 §FR-3).
 */
@Suppress("unused")
class OpenCurrentMergeRequestHandler(
  private val resolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val reader: CurrentBranchGitReader = CurrentBranchGitReader(),
  private val lookup: CurrentBranchMrLookup = CurrentBranchMrLookup(),
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<OpenCurrentMergeRequestHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // selectActiveContext must run on the UI thread (it reads the active editor); a null
    // context is a silent no-op because the resolver already notified the user.
    resolver.selectActiveContext { context ->
      if (context != null) openCurrentMergeRequest(context)
    }
    logger.info("openCurrentMergeRequest requested.")
    return null
  }

  private fun openCurrentMergeRequest(context: RepositoryContext) {
    // The callback may fire on the UI thread (picker dialog case), and the lookup is
    // blocking HTTP + JGit I/O — hop to a background coroutine before doing any of it.
    coroutineScope.launch {
      try {
        val branch = reader.read(File(context.gitDir))
        val info = lookup.lookup(context, branch)
        val mr = info.mr
        if (mr != null) {
          browser.open(mr.webUrl)
        } else {
          NotificationUtils.show("No merge request found for the current branch.")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to open the current branch's merge request.", e)
        NotificationUtils.show("GitLab: Could not open the merge request. Check your token and connection.")
      }
    }
  }
}
