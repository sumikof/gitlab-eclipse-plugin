package com.gitlab.eclipse.lsp.listeners

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResourceChangeEvent
import org.eclipse.core.resources.IResourceChangeListener
import org.eclipse.core.resources.IResourceDelta
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.DidChangeConfigurationParams

class ProjectOpenLanguageServerListener(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope
) : IResourceChangeListener {
  private val logger by lazy { logger<ProjectOpenLanguageServerListener>() }

  init {
    ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE)
  }

  override fun resourceChanged(event: IResourceChangeEvent) {
    var projectSetChanged = false

    event.delta.accept { delta ->
      if (delta.resource is IProject) {
        if (delta.isProjectSetChange()) {
          projectSetChanged = true
        }
        // Deltas below a project describe file/folder changes, which never affect the set of
        // workspace folders — prune the descent.
        false
      } else {
        // Keep descending until we reach the project-level deltas (children of the workspace root).
        true
      }
    }

    // Send at most once per event: a multi-project change (e.g. importing several projects at
    // once) must not queue several near-simultaneous didChangeConfiguration calls (issue #16).
    if (projectSetChanged) {
      coroutineScope.launch {
        logger.info("Sending workspace folders change notification to Language Server.")

        languageServerWrapper.languageServer?.didChangeConfiguration(
          DidChangeConfigurationParams(
            GitLabLanguageServerConfigurationParams(workspaceFolders = workspaceFolders)
          )
        )
      }
    }
  }

  /**
   * True when this project-level delta changes the set of projects the language server should
   * see: project added, removed, or opened/closed. Open/close surfaces as CHANGED with the OPEN
   * flag; bare CHANGED (file-save churn inside the project) must not trigger a resend.
   */
  private fun IResourceDelta.isProjectSetChange(): Boolean = when (kind) {
    IResourceDelta.ADDED, IResourceDelta.REMOVED -> true
    IResourceDelta.CHANGED -> flags and IResourceDelta.OPEN != 0
    else -> false
  }
}
