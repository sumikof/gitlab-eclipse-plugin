package com.gitlab.eclipse.mcp

import java.nio.file.Path

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
