package com.gitlab.eclipse.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Resolves and idempotently provisions the GitLab Duo MCP config files.
 * Pure filesystem logic (no SWT / no network) so it is unit-testable.
 * Mirrors the VS Code extension's mcp_config.ts / mcp_workspace_config.ts.
 */
class McpConfigService(
  private val homeDir: Path = Path.of(System.getProperty("user.home")),
) {
  fun getUserConfigPath(): Path = homeDir.resolve(CONFIG_RELATIVE_PATH)

  fun getWorkspaceConfigPath(workspaceRoot: Path): Path =
    workspaceRoot.resolve(CONFIG_RELATIVE_PATH)

  /**
   * Creates [path] with [DEFAULT_CONFIG_TEMPLATE] if it does not exist yet.
   * Existing files are left untouched (idempotent). If [path] already exists
   * as a directory, this throws so the caller can surface a clear error.
   */
  fun ensureConfigFile(path: Path) {
    if (Files.isDirectory(path)) {
      error("MCP config path exists but is a directory: $path. Please remove or rename it.")
    }
    try {
      path.parent?.let { Files.createDirectories(it) }
      try {
        Files.writeString(path, DEFAULT_CONFIG_TEMPLATE, StandardOpenOption.CREATE_NEW)
      } catch (_: java.nio.file.FileAlreadyExistsException) {
        // Idempotent: keep the user's existing config.
      }
    } catch (e: java.io.IOException) {
      error("Could not create MCP config at $path: ${e.message}")
    }
  }

  companion object {
    private const val CONFIG_RELATIVE_PATH = ".gitlab/duo/mcp.json"

    /** Same default template as the VS Code extension (mcp_config.ts). */
    const val DEFAULT_CONFIG_TEMPLATE = """{
  // GitLab Duo MCP (Model Context Protocol) Configuration
  // This file configures MCP servers that extend GitLab Duo's capabilities.
  //
  // GitLab MCP Documentation: https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_clients/

  "mcpServers": {
    // Add your MCP server configurations here
  }
}"""
  }
}
