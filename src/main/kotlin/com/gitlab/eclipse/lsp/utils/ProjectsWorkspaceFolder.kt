package com.gitlab.eclipse.lsp.utils

import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.WorkspaceFolder

val workspaceFolders
  get() = workspaceFoldersOf(ResourcesPlugin.getWorkspace().root.projects)

/**
 * The workspace folders the language server is sent for [projects], one per project and in the same
 * order. Anything that has to agree with the server about "the first workspace folder" (the MCP
 * workspace configuration) derives it from the same project array through this function.
 */
internal fun workspaceFoldersOf(projects: Array<IProject>): List<WorkspaceFolder> = projects.map {
  WorkspaceFolder(it.locationURI.toASCIIString(), it.name)
}
