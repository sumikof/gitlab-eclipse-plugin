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
    event.delta.accept { delta ->
      if (delta.resource is IProject) {
        if (delta.kind == IResourceChangeEvent.POST_CHANGE) {
          coroutineScope.launch {
            logger.info("Sending workspace folders change notification to Language Server.")

            languageServerWrapper.languageServer?.workspaceService?.didChangeConfiguration(
              DidChangeConfigurationParams(
                GitLabLanguageServerConfigurationParams(workspaceFolders = workspaceFolders)
              )
            )
          }
        }
      }

      true
    }
  }
}
