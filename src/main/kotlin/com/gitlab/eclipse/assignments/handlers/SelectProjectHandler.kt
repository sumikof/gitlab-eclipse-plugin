package com.gitlab.eclipse.assignments.handlers

import com.gitlab.eclipse.api.ProjectDetailService
import com.gitlab.eclipse.assignments.ProjectAssignment
import com.gitlab.eclipse.assignments.SelectedProjectStore
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.File

/**
 * `gl.selectProject`: points a repository at a specific GitLab project (design F8 / §9.7).
 *
 * No project search picker is offered: U3 fixed that the project is entered as a path and then
 * confirmed to exist through the API. That keeps a new GraphQL query out of the plugin and makes
 * the "is this real" step an actual existence check rather than a list the user scrolls.
 *
 * The assignment is saved ONLY after the project has been confirmed to exist, and a failed save is
 * reported as a failure with no success message (A17).
 */
class SelectProjectHandler : AbstractHandler() {
  private val logger by lazy { logger<SelectProjectHandler>() }
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent) {
    val folders = WorkspaceProjectPicker.workspaceRepoDirs()
    if (folders.isEmpty()) {
      notify("GitLab: There is no project in the workspace.")
      return
    }
    val folder = if (folders.size == 1) folders.first() else pickFolder(folders) ?: return
    val remotes = remotesOf(folder)
    if (remotes.isEmpty()) {
      notify("GitLab: That repository has no remote to assign a GitLab project to.")
      return
    }
    val remoteUrl = if (remotes.size == 1) remotes.values.first() else pickRemote(remotes) ?: return
    val projectPath = promptProjectPath() ?: return
    val instanceUrl = service<ScopedPreferenceStore>().getString(PreferenceConstants.GITLAB_INSTANCE_URL)
    if (instanceUrl.isNullOrBlank()) {
      notify("GitLab: Set your GitLab instance URL in the GitLab preferences first.")
      return
    }

    coroutineScope.launch {
      try {
        assign(folder, remoteUrl, projectPath.trim().trim('/'), instanceUrl)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Shared plain-Job scope: an escape cancels every other coroutine on it (issue #16).
        logger.error("Project assignment failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not assign that project. See the Error Log.")
      }
    }
  }

  /** Confirms the project exists before anything is stored — an assignment to nothing is worse
   *  than no assignment, because it silently overrides a resolution that used to work. */
  private fun assign(folder: File, remoteUrl: String, projectPath: String, instanceUrl: String) {
    val encoded = RepositoryContext.encodeProjectId(projectPath)
    val project = try {
      service<ProjectDetailService>().getProject(encoded)
    } catch (e: Exception) {
      logger.info("Assignment rejected: the project lookup failed with ${e.javaClass.name}.")
      uiNotify("GitLab: No project with that path was found on your GitLab instance. Nothing was saved.")
      return
    }
    val assignment = ProjectAssignment(
      repositoryRootPath = folder.canonicalPath,
      remoteUrl = remoteUrl,
      instanceUrl = instanceUrl,
      namespaceWithPath = projectPath,
      projectId = project.id,
    )
    if (!SelectedProjectStore().put(assignment)) {
      // A17: no success message when the store did not take it.
      uiNotify("GitLab: Could not save the assignment. See the Error Log.")
      return
    }
    uiNotify("GitLab: This repository is now assigned to $projectPath.")
  }

  private fun remotesOf(folder: File): Map<String, String> =
    openRepository(folder)?.use { repo ->
      repo.config.getSubsections(REMOTE_SECTION)
        .mapNotNull { name -> repo.config.getString(REMOTE_SECTION, name, URL_KEY)?.let { name to it } }
        .toMap()
    }.orEmpty()

  private fun openRepository(folder: File): Repository? =
    try {
      FileRepositoryBuilder().setGitDir(File(folder, ".git")).setMustExist(true).build()
    } catch (_: Exception) {
      null
    }

  private fun pickFolder(folders: List<File>): File? {
    val labels = folders.associateBy { it.name }
    return labels[pick("Select a repository", labels.keys.toTypedArray())]
  }

  private fun pickRemote(remotes: Map<String, String>): String? {
    val labels = remotes.entries.associate { "${it.key} — ${it.value}" to it.value }
    return labels[pick("Select the remote to assign for", labels.keys.toTypedArray())]
  }

  private fun promptProjectPath(): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = InputDialog(
      shell,
      TITLE,
      "The project path on GitLab, for example group/subgroup/project.",
      "",
      null,
    )
    return if (dialog.open() == Window.OK) dialog.value?.takeIf { it.isNotBlank() } else null
  }

  private fun pick(message: String, elements: Array<String>): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle(TITLE)
    dialog.setMessage(message)
    dialog.setElements(elements)
    return if (dialog.open() == Window.OK) dialog.firstResult as? String else null
  }

  private fun notify(message: String) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    MessageDialog.openInformation(shell, "GitLab", message)
  }

  private fun uiNotify(message: String) = currentDisplay.asyncExec { notify(message) }

  private companion object {
    const val TITLE = "Select GitLab Project"
    const val REMOTE_SECTION = "remote"
    const val URL_KEY = "url"
  }
}
