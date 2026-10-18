package com.gitlab.eclipse.mcp

/**
 * User-facing notices of "Open MCP User Configuration". They name the environment variable at most:
 * never its value, a path or an exception message, by design.
 */
internal object McpUserConfigMessages {
  const val CREATE_FAILED = "Could not create the MCP user configuration. See the Error Log."
  const val OPEN_FAILED = "Could not open the MCP user configuration. See the Error Log."

  fun unavailable(reason: McpUserConfigResolution.Unavailable): String = when (reason) {
    is McpUserConfigResolution.Missing -> when (reason.source) {
      McpUserConfigSource.APPDATA ->
        "GitLab Duo reads no MCP user configuration because the APPDATA environment variable is not set."
      else -> "Could not find your home directory, so the MCP user configuration cannot be opened."
    }
    is McpUserConfigResolution.NotAbsolute ->
      "The MCP user configuration cannot be opened: ${reason.source.name} is not an absolute path. " +
        "Set it to an absolute path and restart Eclipse."
    is McpUserConfigResolution.Invalid ->
      "The MCP user configuration cannot be opened: ${reason.source.name} is not a valid path. " +
        "Fix it and restart Eclipse."
  }
}
