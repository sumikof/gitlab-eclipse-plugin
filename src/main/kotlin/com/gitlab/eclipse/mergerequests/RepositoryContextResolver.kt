package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IFile
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ElementListSelectionDialog
import java.io.File
import java.io.IOException

/**
 * Resolves the workspace/active-editor selection into a single [RepositoryContext] (Phase 3 §6.1).
 *
 * Not registered as a Koin single on purpose: both dependencies are default-constructible, so this
 * follows the handler/[WorkspaceProjectPicker] pattern of default-argument construction.
 *
 * Threading: call the public methods on the UI thread — they read the active editor through the
 * workbench, and the selection must be fixed at call time. Candidate enumeration and JGit reads run
 * synchronously on the calling thread (the same work the Phase 2 picker performs); only the
 * multi-candidate dialog self-marshals onto the display thread, mirroring the Phase 2 picker, so
 * [selectActiveContext]'s callback runs either synchronously or later on the UI thread.
 */
class RepositoryContextResolver(
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val resolver: GitLabProjectUrlResolver = GitLabProjectUrlResolver(),
) {
  private val logger by lazy { logger<RepositoryContextResolver>() }

  /**
   * All GitLab-resolvable workspace repos, deduped by canonical gitDir. Dirs without a GitLab
   * remote (or with any resolution failure) are skipped — never throws for a bad repo.
   */
  fun candidateContexts(): List<RepositoryContext> {
    val infos = WorkspaceProjectPicker.workspaceRepoDirs().mapNotNull { dir ->
      (resolver.resolveContextForRepo(dir) as? GitLabProjectUrlResolver.ContextResolution.Ok)?.project
    }
    // A multi-module repo imported as several Eclipse projects yields the same gitDir many
    // times; dedup keeps the first occurrence, and firstOrNull maps it back to its info.
    val dedupedGitDirs = RepositoryContext.dedupByCanonicalPath(infos.map { it.gitDir })
    return dedupedGitDirs.mapNotNull { gitDir -> infos.firstOrNull { it.gitDir == gitDir }?.toContext() }
  }

  /**
   * Command-invocation selection (interactive): active editor's repo if its input has an [IFile],
   * else 0 candidates → notify + null, 1 → that one, many → picker dialog. Cancel → null silently.
   * Candidates are determined synchronously at call time; only the dialog defers [onResult].
   */
  fun selectActiveContext(onResult: (RepositoryContext?) -> Unit) {
    val activeFile = activeEditorFile()
    if (activeFile != null) {
      when (val resolved = resolver.resolveContextForFile(activeFile)) {
        is GitLabProjectUrlResolver.ContextResolution.Ok -> onResult(resolved.project.toContext())
        is GitLabProjectUrlResolver.ContextResolution.Warn -> {
          NotificationUtils.show(resolved.message)
          onResult(null)
        }
      }
      return
    }
    val candidates = candidateContexts()
    when {
      candidates.isEmpty() -> {
        NotificationUtils.show(NO_REPOSITORY_FOUND)
        onResult(null)
      }
      candidates.size == 1 -> onResult(candidates[0])
      else -> currentDisplay.asyncExec { promptForContext(candidates, onResult) }
    }
  }

  /**
   * Non-interactive variant for the sidebar's auto-refresh: never notifies, never shows a picker.
   * Active editor's repo if resolvable, else the single deduped candidate, else null.
   */
  fun activeOrSingleContext(): RepositoryContext? {
    val activeFile = activeEditorFile()
    if (activeFile != null) {
      val resolved = resolver.resolveContextForFile(activeFile)
      if (resolved is GitLabProjectUrlResolver.ContextResolution.Ok) return resolved.project.toContext()
    }
    return candidateContexts().singleOrNull()
  }

  private fun activeEditorFile(): File? = platformUtils.getActiveTextEditor()
    ?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()

  private fun promptForContext(
    candidates: List<RepositoryContext>,
    onResult: (RepositoryContext?) -> Unit,
  ) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = ElementListSelectionDialog(shell, ContextLabelProvider())
    dialog.setTitle("Select GitLab Project")
    dialog.setMessage("Multiple GitLab projects were found. Choose one:")
    dialog.setElements(candidates.toTypedArray())
    if (dialog.open() == Window.OK) {
      onResult(dialog.firstResult as? RepositoryContext)
    } else {
      onResult(null)
    }
    logger.info("Repository context picker closed.")
  }

  private fun GitLabProjectInfo.toContext(): RepositoryContext = RepositoryContext(
    gitDir = canonicalOrAbsolutePath(gitDir),
    workTree = canonicalOrAbsolutePath(workTree),
    namespaceWithPath = namespaceWithPath,
    instanceUrl = instanceUrl,
    webUrl = webUrl,
    remoteName = remoteName,
    projectId = RepositoryContext.encodeProjectId(namespaceWithPath),
  )

  private fun canonicalOrAbsolutePath(file: File): String = try {
    file.canonicalPath
  } catch (_: IOException) {
    file.absolutePath
  }

  private class ContextLabelProvider : LabelProvider() {
    override fun getText(element: Any?): String =
      (element as? RepositoryContext)?.webUrl ?: super.getText(element)
  }

  private companion object {
    const val NO_REPOSITORY_FOUND = "No GitLab repository found in the workspace."
  }
}
