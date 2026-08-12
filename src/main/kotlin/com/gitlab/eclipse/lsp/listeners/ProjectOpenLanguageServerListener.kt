package com.gitlab.eclipse.lsp.listeners

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResourceChangeEvent
import org.eclipse.core.resources.IResourceChangeListener
import org.eclipse.core.resources.IResourceDelta
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.DidChangeConfigurationParams

class ProjectOpenLanguageServerListener(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope,
  private val outboundLock: Mutex,
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
        try {
          logger.info("Sending workspace folders change notification to Language Server.")

          // Under the same lock as every other outbound notification, and reading
          // `workspaceFolders` inside it. This is a partial configuration, but `workspaceFolders`
          // is a key the FULL configuration also carries, so an unsynchronised send here can be
          // overtaken by a full send that was built before this project appeared — and the server
          // keeps the older list until something sends again (issue #16).
          outboundLock.withLock {
            languageServerWrapper.languageServer?.didChangeConfiguration(
              DidChangeConfigurationParams(
                GitLabLanguageServerConfigurationParams(workspaceFolders = workspaceFolders)
              )
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // `workspaceFolders` reads ResourcesPlugin.getWorkspace() (IllegalStateException once
          // the resources bundle winds down, e.g. project deletion near shutdown) and a nullable
          // locationURI. This runs on the SHARED plain-Job scope — an escape would cancel it and
          // every other coroutine on it (same shape as `launchCiWrite`). Type only: no URIs/paths.
          logger.error("Workspace folders change notification failed: ${e.javaClass.name}")
        }
      }
    }
  }

  /**
   * True for project added/removed, and for open/close (CHANGED with the OPEN flag); bare
   * CHANGED (file-save churn inside the project) must not trigger a resend. Note that
   * [workspaceFolders] maps `root.projects` with no isOpen filter and closed projects keep
   * their locationURI, so today an open/close resends a byte-identical folder list; matching
   * OPEN becomes load-bearing if [workspaceFolders] ever filters closed projects.
   */
  private fun IResourceDelta.isProjectSetChange(): Boolean = when (kind) {
    IResourceDelta.ADDED, IResourceDelta.REMOVED -> true
    IResourceDelta.CHANGED -> flags and IResourceDelta.OPEN != 0
    else -> false
  }
}
