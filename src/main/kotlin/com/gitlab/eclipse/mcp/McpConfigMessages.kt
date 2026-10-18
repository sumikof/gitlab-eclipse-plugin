package com.gitlab.eclipse.mcp

/** User-facing notices of the MCP config commands. No paths and no exception messages, by design. */
internal object McpConfigMessages {
  const val OPEN_FAILED = "Could not open the MCP workspace configuration. See the Error Log."

  fun openedIn(projectName: String) =
    "Opened the MCP workspace configuration of project '$projectName'. " +
      "GitLab Duo reads it from the first project in the workspace, sorted by name."
}
