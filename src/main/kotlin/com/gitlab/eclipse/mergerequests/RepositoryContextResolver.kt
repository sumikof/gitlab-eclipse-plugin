package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * Threading: only the workbench read is UI-thread work — [activeEditorFile] must run on the UI
 * thread, and [selectActiveContext] must be called on it so the active-editor selection is fixed
 * at call time. All JGit work (candidate enumeration, remote resolution) is blocking local I/O
 * and runs OFF the UI thread: [selectActiveContext] does it on the shared IO [CoroutineScope] and
 * marshals the picker/callback back via `asyncExec`, while [candidateContexts] and
 * [resolveNonInteractive] never touch the workbench so callers can (and should) invoke them from
 * a background coroutine, mirroring [WorkspaceProjectPicker.pickWebUrl].
 */
class RepositoryContextResolver(
  private val platformUtils: PlatformUtils = PlatformUtils(),
  private val resolver: GitLabProjectUrlResolver = GitLabProjectUrlResolver(),
) {
  private val logger by lazy { logger<RepositoryContextResolver>() }
  private val coroutineScope by lazyService<CoroutineScope>()

  /**
   * All GitLab-resolvable workspace repos, deduped by canonical gitDir. Dirs without a GitLab
   * remote (or with any resolution failure) are skipped — never throws for a bad repo.
   * Blocking JGit I/O, no workbench access: call from a background thread.
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
   * The active text editor's file, or null. Pure workbench read — no JGit. UI thread ONLY:
   * off the UI thread the workbench silently reports no active window and this yields null.
   * The result is what [selectActiveContext]/[resolveNonInteractive] resolve to the enclosing
   * repository on a background thread (deriving the gitDir here would already be JGit work).
   */
  fun activeEditorFile(): File? = platformUtils.getActiveTextEditor()
    ?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()

  /**
   * Command-invocation selection (interactive): active editor's repo if its input has an [IFile],
   * else 0 candidates → notify + null, 1 → that one, many → picker dialog. Cancel → null silently.
   *
   * Call on the UI thread: the active-editor selection is captured synchronously at call time,
   * then JGit resolution runs on the IO scope, and [onResult] (plus any notification or picker)
   * runs later on the UI thread via `asyncExec`. Never throws.
   */
  fun selectActiveContext(onResult: (RepositoryContext?) -> Unit) {
    // UI thread, synchronously at call time — fixes the selection before execute() returns.
    val activeFile = activeEditorFile()
    coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch or every other
      // consumer of the scope loses its coroutines.
      try {
        val selection = resolveInteractive(activeFile)
        currentDisplay.asyncExec { deliver(selection, onResult) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to resolve the repository context.", e)
        currentDisplay.asyncExec { onResult(null) }
      }
    }
  }

  /**
   * Non-interactive variant for the sidebar's auto-refresh: never notifies, never shows a picker.
   * The [activeEditorFile]'s repo if resolvable, else the single deduped candidate, else null.
   *
   * Blocking JGit I/O, no workbench access: call from a background thread and pass the
   * UI-thread-captured [activeEditorFile] (from [RepositoryContextResolver.activeEditorFile]).
   */
  fun resolveNonInteractive(activeEditorFile: File?): RepositoryContext? {
    if (activeEditorFile != null) {
      val resolved = resolver.resolveContextForFile(activeEditorFile)
      if (resolved is GitLabProjectUrlResolver.ContextResolution.Ok) return resolved.project.toContext()
    }
    return candidateContexts().singleOrNull()
  }

  /** Background thread: JGit resolution only — decides what [deliver] does on the UI thread. */
  private fun resolveInteractive(activeEditorFile: File?): Selection {
    if (activeEditorFile != null) {
      // An explicit editor context that fails to resolve aborts with its Warn message —
      // no fallback to workspace candidates.
      return when (val resolved = resolver.resolveContextForFile(activeEditorFile)) {
        is GitLabProjectUrlResolver.ContextResolution.Ok -> Selection.Resolved(resolved.project.toContext())
        is GitLabProjectUrlResolver.ContextResolution.Warn -> Selection.Aborted(resolved.message)
      }
    }
    val candidates = candidateContexts()
    return when {
      candidates.isEmpty() -> Selection.Aborted(NO_REPOSITORY_FOUND)
      candidates.size == 1 -> Selection.Resolved(candidates[0])
      else -> Selection.Prompt(candidates)
    }
  }

  /** UI thread (asyncExec): the only place [onResult] is invoked. */
  private fun deliver(selection: Selection, onResult: (RepositoryContext?) -> Unit) {
    when (selection) {
      is Selection.Resolved -> onResult(selection.context)
      is Selection.Aborted -> {
        NotificationUtils.show(selection.message)
        onResult(null)
      }
      is Selection.Prompt -> promptForContext(selection.candidates, onResult)
    }
  }

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

  /** Outcome of the background resolution, delivered on the UI thread by [deliver]. */
  private sealed interface Selection {
    data class Resolved(val context: RepositoryContext) : Selection
    data class Aborted(val message: String) : Selection
    data class Prompt(val candidates: List<RepositoryContext>) : Selection
  }

  private class ContextLabelProvider : LabelProvider() {
    override fun getText(element: Any?): String =
      (element as? RepositoryContext)?.webUrl ?: super.getText(element)
  }

  private companion object {
    const val NO_REPOSITORY_FOUND = "No GitLab repository found in the workspace."
  }
}
