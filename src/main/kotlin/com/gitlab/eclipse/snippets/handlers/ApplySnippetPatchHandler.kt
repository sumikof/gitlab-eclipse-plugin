package com.gitlab.eclipse.snippets.handlers

import com.gitlab.eclipse.api.SnippetQueryService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.snippets.PatchApplyMessages
import com.gitlab.eclipse.snippets.PatchApplyOutcome
import com.gitlab.eclipse.snippets.PatchSnippetCandidate
import com.gitlab.eclipse.snippets.PatchSnippetFilter
import com.gitlab.eclipse.snippets.SnippetPatchApplyService
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRunnable
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import java.io.File

/**
 * `gl.applySnippetPatch`: applies a `.patch` snippet to the current repository's working tree
 * (design F4).
 *
 * The shape follows [CreateSnippetPatchHandler] — dialogs on the UI thread, GraphQL and JGit in the
 * background — with one addition that the other snippet commands do not need: the apply, the
 * rollback and the workspace refresh all run inside a single [IWorkspace.run] holding a scheduling
 * rule, so Eclipse's own saves cannot land between the comparison and the write (design §9.4 /
 * §15.2).
 *
 * The rule is the workspace root, deliberately coarse. Files under a git working tree are not
 * necessarily workspace resources at all, and the root is the only rule guaranteed to cover the
 * ones that are. Applying a patch is short and rare, so the breadth costs little.
 */
class ApplySnippetPatchHandler : AbstractHandler() {
  private val logger by lazy { logger<ApplySnippetPatchHandler>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val platformUtils = PlatformUtils()

  override fun execute(event: ExecutionEvent) {
    val file = platformUtils.getActiveTextEditor()
      ?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
    if (file == null) {
      notify("GitLab: Open a file in the repository you want to apply a patch to.")
      return
    }

    coroutineScope.launch {
      try {
        start(file)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Shared plain-Job scope: an escape cancels every other coroutine on it (issue #16).
        // Type only — the response carries snippet text and file paths.
        logger.error("Patch snippet listing failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not list snippets. See the Error Log.")
      }
    }
  }

  private fun start(file: File) {
    val context = GitLabProjectUrlResolver().resolveContextForFile(file)
    if (context !is GitLabProjectUrlResolver.ContextResolution.Ok) {
      logger.info("Patch application skipped: no GitLab project resolved for the active file.")
      uiNotify("GitLab: The current file is not in a GitLab project repository.")
      return
    }
    val project = context.project
    val accept: (String) -> Boolean = { candidate -> candidate == project.instanceUrl }
    val page = service<SnippetQueryService>().listSnippets(project.namespaceWithPath, accept) ?: return
    val candidates = PatchSnippetFilter.candidates(page.snippets)
    if (candidates.isEmpty()) {
      uiNotify("GitLab: There are no snippets holding a .patch file.")
      return
    }
    currentDisplay.asyncExec { continueOnUiThread(project, candidates, page.hasNextPage, accept) }
  }

  /** Runs on the UI thread: the unsaved-editor check reads the workbench and the picker is SWT. */
  private fun continueOnUiThread(
    project: GitLabProjectInfo,
    candidates: List<PatchSnippetCandidate>,
    hasNextPage: Boolean,
    accept: (String) -> Boolean,
  ) {
    if (hasNextPage) {
      // Never silently truncate (design section 11).
      notify("GitLab: Showing the first page of snippets only; more exist on the server.")
    }
    // Design §9.4-1: an early stop for the user's benefit, not the safety argument. Saving for
    // them is deliberately not done — which buffer wins is their decision, not ours.
    if (hasUnsavedEditorsUnder(project.workTree)) {
      notify("GitLab: Save your changes in this repository before applying a patch.")
      return
    }
    val chosen = pick(candidates) ?: return
    fetchAndApply(project, chosen, accept)
  }

  private fun fetchAndApply(
    project: GitLabProjectInfo,
    candidate: PatchSnippetCandidate,
    accept: (String) -> Boolean,
  ) {
    coroutineScope.launch {
      try {
        val patchText = service<SnippetQueryService>()
          .fetchBlobContent(candidate.snippet.id, candidate.blob.path, accept)
        if (patchText == null) {
          uiNotify("GitLab: Could not read that patch file.")
          return@launch
        }
        uiNotify(PatchApplyMessages.of(applyUnderRule(project, patchText)))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Patch application failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not apply the patch. See the Error Log.")
      }
    }
  }

  /**
   * Holds the rule across the apply, the rollback and the refresh — the interval the design
   * requires to be one unit (§9.4). The refresh runs whatever the outcome (A4): a rollback also
   * changed files on disk behind Eclipse's back.
   */
  private fun applyUnderRule(project: GitLabProjectInfo, patchText: String): PatchApplyOutcome {
    var outcome: PatchApplyOutcome = PatchApplyOutcome.Failed(IllegalStateException::class.java.name)
    val workspace = ResourcesPlugin.getWorkspace()
    val runnable = IWorkspaceRunnable {
      try {
        outcome = service<SnippetPatchApplyService>()
          .apply(project.gitDir, project.workTree, patchText)
      } finally {
        refreshBestEffort()
      }
    }
    workspace.run(runnable, workspace.root, IWorkspace.AVOID_UPDATE, null)
    return outcome
  }

  /** JGit and this plugin both write the disk directly, so the resource tree would stay stale. */
  private fun refreshBestEffort() {
    try {
      ResourcesPlugin.getWorkspace().root.refreshLocal(IResource.DEPTH_INFINITE, null)
    } catch (e: Exception) {
      logger.warn("Workspace refresh after patch application failed; refresh projects manually.", e)
    }
  }

  /** True when any open editor with unsaved changes belongs to a file under [workTree]. */
  private fun hasUnsavedEditorsUnder(workTree: File): Boolean {
    val root = workTree.canonicalFile
    return PlatformUI.getWorkbench().workbenchWindows
      .flatMap { window -> window.pages.toList() }
      .flatMap { page -> page.dirtyEditors.toList() }
      .any { editor ->
        val location = editor.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
        location != null && location.canonicalFile.startsWith(root)
      }
  }

  private fun pick(candidates: List<PatchSnippetCandidate>): PatchSnippetCandidate? {
    val labels = candidates.associateBy { it.label }
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, LabelProvider())
    dialog.setTitle("Apply Snippet Patch")
    dialog.setMessage("Select a patch to apply")
    dialog.setElements(labels.keys.toTypedArray())
    return if (dialog.open() == Window.OK) labels[dialog.firstResult as? String] else null
  }

  private fun notify(message: String) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    MessageDialog.openInformation(shell, "GitLab", message)
  }

  private fun uiNotify(message: String) = currentDisplay.asyncExec { notify(message) }
}
