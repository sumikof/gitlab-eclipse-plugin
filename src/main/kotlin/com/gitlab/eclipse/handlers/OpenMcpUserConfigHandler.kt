package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.mcp.McpConfigService
import com.gitlab.eclipse.mcp.openMcpConfigInEditor
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

@Suppress("unused")
class OpenMcpUserConfigHandler : AbstractHandler() {
  private val logger = logger<OpenMcpUserConfigHandler>()
  private val service = McpConfigService()

  override fun execute(event: ExecutionEvent): Any? {
    val path = service.getUserConfigPath()
    try {
      service.ensureConfigFile(path)
      openMcpConfigInEditor(path)
      logger.info("Opened MCP user config at $path")
    } catch (e: IllegalStateException) {
      logger.error("Could not open MCP user config.", e)
    } catch (e: org.eclipse.ui.PartInitException) {
      logger.error("Could not open an editor for the MCP user config at $path.", e)
    }
    return null
  }
}
