package com.gitlab.eclipse.snippets.handlers

import com.gitlab.eclipse.api.SnippetService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.snippets.SnippetPatchBuilder
import com.gitlab.eclipse.snippets.SnippetPatchSource
import com.gitlab.eclipse.snippets.SnippetProjectIdResolver
import com.gitlab.eclipse.snippets.SnippetVisibility
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import java.io.File

/**
 * `gl.createSnippetPatch`: uploads the working-tree diff as a `.patch` snippet (design F3).
 *
 * The dialogs run on the UI thread before any coroutine starts; the JGit read and the REST call
 * run in the background. Same shape as [CreateSnippetHandler].
 */
class CreateSnippetPatchHandler : AbstractHandler() {
  private val logger by lazy { logger<CreateSnippetPatchHandler>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val platformUtils = PlatformUtils()

  override fun execute(event: ExecutionEvent) {
    val file = platformUtils.getActiveTextEditor()
      ?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
    if (file == null) {
      notify("GitLab: Open a file in the repository you want to create a patch for.")
      return
    }
    // Cancelling either dialog must leave no trace: no read, no request (A15).
    val name = promptName() ?: return
    val visibility = promptVisibility() ?: return

    coroutineScope.launch {
      try {
        upload(file, name, visibility)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Shared plain-Job scope: an escape cancels every other coroutine on it (issue #16).
        // Type only — the diff carries file contents and the response carries a url.
        logger.error("Patch snippet creation failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not create the patch snippet. See the Error Log.")
      }
    }
  }

  private fun upload(file: File, name: String, visibility: SnippetVisibility) {
    val context = GitLabProjectUrlResolver().resolveContextForFile(file)
    if (context !is GitLabProjectUrlResolver.ContextResolution.Ok) {
      logger.info("Patch snippet skipped: no GitLab project resolved for the active file.")
      uiNotify("GitLab: The current file is not in a GitLab project repository.")
      return
    }
    val project = context.project
    val source = SnippetPatchSource().read(project.gitDir)
    if (source == null) {
      uiNotify("GitLab: The repository has no commit to create a patch against.")
      return
    }
    when (val built = SnippetPatchBuilder.build(name, source.diff, source.commitDescriptor, visibility)) {
      is SnippetPatchBuilder.Result.Ok -> {
        val projectId = SnippetProjectIdResolver().resolve(project.namespaceWithPath)
        val webUrl = service<SnippetService>().create(projectId, built.payload) { candidate ->
          candidate == project.instanceUrl
        } ?: return
        BrowserLauncher().open(webUrl)
      }
      SnippetPatchBuilder.Result.NoChanges ->
        uiNotify("GitLab: There are no working-tree changes to turn into a patch.")
      SnippetPatchBuilder.Result.BinaryNotSupported ->
        uiNotify("GitLab: The changes include a binary file, which cannot be applied from a patch.")
      SnippetPatchBuilder.Result.InvalidName ->
        uiNotify("GitLab: The patch name cannot be empty.")
    }
  }

  private fun promptName(): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = InputDialog(
      shell,
      "Create Snippet Patch",
      "The name is used as the snippet title and as the filename (with .patch appended).",
      "",
      null,
    )
    return if (dialog.open() == Window.OK) dialog.value else null
  }

  private fun promptVisibility(): SnippetVisibility? {
    // Private first: the default must be the one that cannot expose the diff.
    val labels = linkedMapOf(
      "Private — visible only to project members" to SnippetVisibility.PRIVATE,
      "Public — accessible without authentication" to SnippetVisibility.PUBLIC,
    )
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle("Create Snippet Patch")
    dialog.setMessage("Select privacy level")
    dialog.setElements(labels.keys.toTypedArray())
    return if (dialog.open() == Window.OK) labels[dialog.firstResult as? String] else null
  }

  private fun notify(message: String) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    MessageDialog.openInformation(shell, "GitLab", message)
  }

  private fun uiNotify(message: String) = currentDisplay.asyncExec { notify(message) }
}
