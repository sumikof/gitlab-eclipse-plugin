package com.gitlab.eclipse.lsp.utils

import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.WorkspaceFolder

val workspaceFolders
  get() = ResourcesPlugin.getWorkspace().root.projects.map {
    WorkspaceFolder(it.locationURI.toASCIIString(), it.name)
  }
