package com.gitlab.eclipse.mcp

/** User-facing notices of the MCP config commands. No paths and no exception messages, by design. */
internal object McpConfigMessages {
  const val NO_PROJECT = "Open a project to use the MCP workspace configuration."
  const val OPEN_FAILED = "Could not open the MCP workspace configuration. See the Error Log."

  fun closed(projectName: String) =
    "Project '$projectName' is closed. GitLab Duo reads the MCP workspace configuration from the first project " +
      "in the workspace, sorted by name: open it and run the command again."

  fun unavailable(projectName: String) =
    "Project '$projectName' is not on the local file system, so the MCP workspace configuration cannot be created in it."

  fun failed(projectName: String) =
    "Could not create the MCP workspace configuration in project '$projectName'. See the Error Log."

  fun openedIn(projectName: String) =
    "Opened the MCP workspace configuration of project '$projectName'. " +
      "GitLab Duo reads it from the first project in the workspace, sorted by name."

  fun refusal(refusal: McpWorkspaceConfigResolution.Refusal): String = when (refusal) {
    McpWorkspaceConfigResolution.NoProject -> NO_PROJECT
    is McpWorkspaceConfigResolution.Closed -> closed(refusal.projectName)
    is McpWorkspaceConfigResolution.Unavailable -> unavailable(refusal.projectName)
  }
}
