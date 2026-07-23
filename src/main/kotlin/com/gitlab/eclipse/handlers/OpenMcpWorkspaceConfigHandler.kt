package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.mcp.McpConfigService
import com.gitlab.eclipse.mcp.openMcpConfigInEditor
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.ResourcesPlugin

@Suppress("unused")
class OpenMcpWorkspaceConfigHandler : AbstractHandler() {
  private val logger = logger<OpenMcpWorkspaceConfigHandler>()
  private val service = McpConfigService()

  override fun execute(event: ExecutionEvent): Any? {
    val root = ResourcesPlugin.getWorkspace().root.location
    if (root == null) {
      logger.warn("No workspace location available; cannot open workspace MCP config.")
      return null
    }
    val path = service.getWorkspaceConfigPath(root.toFile().toPath())
    try {
      service.ensureConfigFile(path)
      openMcpConfigInEditor(path)
      logger.info("Opened MCP workspace config at $path")
    } catch (e: IllegalStateException) {
      logger.error("Could not open MCP workspace config.", e)
    }
    return null
  }
}
