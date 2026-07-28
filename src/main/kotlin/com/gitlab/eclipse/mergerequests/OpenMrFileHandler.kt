package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.ChangedFileNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.Path
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.ide.IDE
import java.io.File

/**
 * Opens the local working-tree copy of the changed file selected under a merge request in the
 * GitLab sidebar (VSCode `gl.openMrFile` parity, Phase 3 §8.6).
 *
 * Three gates, in order, all satisfied or the command notifies and does nothing:
 * 1. Repository: the node's [ChangedFileNode.mrWebUrl] must match exactly ONE workspace
 *    repository's project web URL (same predicate as [CheckoutMrBranchHandler]).
 * 2. Revision: that repository's HEAD sha must equal the node's [ChangedFileNode.diffHeadSha] —
 *    otherwise the file on disk is not the MR's version and opening it would be misleading.
 * 3. Containment: the diff path (server-supplied data) must resolve — symlinks and `..`
 *    included — to a real path inside the work tree ([resolveContainedRealPath]).
 */
@Suppress("unused")
class OpenMrFileHandler(
  private val resolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val gitReader: CurrentBranchGitReader = CurrentBranchGitReader(),
) : AbstractHandler() {
  private val logger = logger<OpenMrFileHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: capture the selection synchronously before hopping to the background.
    val node = selectedChangedFile(event)
    val relPath = node?.let { it.newPath ?: it.oldPath }
    when {
      node == null -> NotificationUtils.show("Select a changed file in the GitLab sidebar first.")
      relPath.isNullOrBlank() -> NotificationUtils.show("The selected change has no file path.")
      node.mrWebUrl == null ->
        NotificationUtils.show("Cannot determine this file's merge request; refresh the sidebar.")
      node.diffHeadSha.isNullOrBlank() -> NotificationUtils.show(CHECKOUT_FIRST_MESSAGE)
      else -> openInBackground(node.mrWebUrl, node.diffHeadSha, relPath)
    }
    logger.info("openMrFile requested.")
    return null
  }

  /** The [ChangedFileNode] the command was invoked on: the sidebar context-menu selection,
   *  falling back to the window's current selection. */
  private fun selectedChangedFile(event: ExecutionEvent): ChangedFileNode? {
    val selection = HandlerUtil.getActiveMenuSelection(event)
      ?: HandlerUtil.getCurrentSelection(event)
    return (selection as? IStructuredSelection)?.firstElement as? ChangedFileNode
  }

  private fun openInBackground(mrWebUrl: String, diffHeadSha: String, relPath: String) {
    // Everything below is blocking local I/O (JGit enumeration, HEAD read, real-path
    // resolution) — background only. NotificationUtils self-marshals to the UI thread.
    coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch or every other
      // consumer of the scope loses its coroutines.
      try {
        val matches = resolver.candidateContexts().filter { mrBelongsTo(it, mrWebUrl) }
        when {
          matches.isEmpty() ->
            NotificationUtils.show("No workspace repository matches this merge request's project.")
          matches.size > 1 ->
            NotificationUtils.show(
              "Multiple workspace repositories match this merge request; cannot choose one.",
            )
          else -> resolveAndOpen(matches[0], diffHeadSha, relPath)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to open the merge request file.", e)
        NotificationUtils.show(OPEN_FAILED_MESSAGE)
      }
    }
  }

  /** Background thread: the revision gate, then the containment gate, then the UI-thread open. */
  private fun resolveAndOpen(context: RepositoryContext, diffHeadSha: String, relPath: String) {
    val headSha = gitReader.read(File(context.gitDir)).headSha
    if (headSha == null || headSha != diffHeadSha) {
      NotificationUtils.show(CHECKOUT_FIRST_MESSAGE)
      return
    }
    val file = resolveContainedRealPath(File(context.workTree), relPath)
    if (file == null) {
      NotificationUtils.show("File not found in the working tree.")
      return
    }
    currentDisplay.asyncExec { openEditorFor(file) }
  }

  /** UI thread. Prefers the workspace [org.eclipse.core.resources.IFile] mapping (project-aware
   *  editor); a work-tree file outside any Eclipse project falls back to the EFS file store. */
  private fun openEditorFor(file: File) {
    try {
      val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
      if (page == null) {
        NotificationUtils.show(OPEN_FAILED_MESSAGE)
        return
      }
      val iFile = ResourcesPlugin.getWorkspace().root.getFileForLocation(Path(file.absolutePath))
      if (iFile != null && iFile.exists()) {
        IDE.openEditor(page, iFile)
      } else {
        IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toURI()))
      }
    } catch (e: Exception) {
      logger.error("Failed to open editor for ${file.path}.", e)
      NotificationUtils.show(OPEN_FAILED_MESSAGE)
    }
  }

  /** True when [mrWebUrl] lives under [context]'s project web URL. */
  private fun mrBelongsTo(context: RepositoryContext, mrWebUrl: String): Boolean =
    mrWebUrl.startsWith("${context.webUrl.trimEnd('/')}/-/merge_requests/")

  private companion object {
    const val CHECKOUT_FIRST_MESSAGE =
      "Check out the merge request branch first (the working tree does not match this merge request)."
    const val OPEN_FAILED_MESSAGE = "Failed to open the file — see the Error Log."
  }
}
