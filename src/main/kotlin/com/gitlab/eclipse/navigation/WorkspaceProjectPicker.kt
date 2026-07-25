package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import java.io.File

/** Resolves "the project webUrl for the current context", prompting on multi-repo workspaces (U-1). */
class WorkspaceProjectPicker(
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val resolver: GitLabProjectUrlResolver = GitLabProjectUrlResolver(),
) {
  private val logger by lazy { logger<WorkspaceProjectPicker>() }
  private val coroutineScope by lazyService<CoroutineScope>()

  /**
   * Invokes onResult with Ok(webUrl) or Warn(message). The active-editor read happens synchronously
   * on the calling (UI) thread before any coroutine starts; JGit resolution runs on a background
   * thread. onResult may run on a background thread (0/1-repo cases) or on the UI thread (dialog).
   */
  fun pickWebUrl(onResult: (GitLabProjectUrlResolver.Resolution) -> Unit) {
    // SWT/workbench access: must stay on the calling (UI) thread, before the coroutine launches.
    val activeFile = platformUtils.getActiveTextEditor()
      ?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
    coroutineScope.launch {
      if (activeFile != null) {
        onResult(resolver.resolveWebUrlForFile(activeFile))
        return@launch
      }
      val resolved = workspaceRepoDirs().mapNotNull { dir ->
        (resolver.resolveWebUrlForRepo(dir) as? GitLabProjectUrlResolver.Resolution.Ok)?.let { dir to it.url }
      }
      when {
        resolved.isEmpty() -> onResult(GitLabProjectUrlResolver.Resolution.Warn(NO_PROJECT_FOUND))
        resolved.size == 1 -> onResult(GitLabProjectUrlResolver.Resolution.Ok(resolved[0].second))
        else -> currentDisplay.asyncExec { promptForRepo(resolved, onResult) }
      }
    }
  }

  private fun workspaceRepoDirs(): List<File> =
    ResourcesPlugin.getWorkspace().root.projects
      .filter { it.isAccessible }
      .mapNotNull { it.location?.toFile() }

  private fun promptForRepo(
    choices: List<Pair<File, String>>,
    onResult: (GitLabProjectUrlResolver.Resolution) -> Unit,
  ) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle("Select GitLab Project")
    dialog.setMessage("Multiple GitLab projects were found. Choose one:")
    dialog.setElements(choices.map { it.second }.toTypedArray())
    if (dialog.open() == Window.OK) {
      (dialog.firstResult as? String)?.let { onResult(GitLabProjectUrlResolver.Resolution.Ok(it)) }
    }
    logger.info("Project picker closed.")
  }

  private companion object {
    const val NO_PROJECT_FOUND = "No GitLab project found in the workspace."
  }
}
