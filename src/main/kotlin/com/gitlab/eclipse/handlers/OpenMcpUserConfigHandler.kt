package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.mcp.McpConfigService
import com.gitlab.eclipse.mcp.McpUserConfigLocation
import com.gitlab.eclipse.mcp.McpUserConfigMessages
import com.gitlab.eclipse.mcp.McpUserConfigResolution
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.openInActiveEditor
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.filesystem.EFS
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil
import java.nio.file.Path

/**
 * "Open MCP User Configuration": creates (if missing, never overwriting) and opens the user-scoped
 * `mcp.json` **where the language server reads it** — [McpUserConfigLocation] — which depends on the OS
 * and on `APPDATA` / `XDG_CONFIG_HOME` / `HOME`. When the server would read no file, or one this command
 * cannot reproduce (a relative or invalid value), nothing is created and a notice says why: opening
 * another file would let the user edit a config the server ignores.
 *
 * Runs on the UI thread, as before: one small local file, and opening the editor reads it there anyway.
 * Notices and logs carry the variable name at most — never a path, a value or an exception message.
 */
@Suppress("unused")
class OpenMcpUserConfigHandler(
  private val location: McpUserConfigLocation = McpUserConfigLocation(),
  private val service: McpConfigService = McpConfigService(),
  private val openEditor: (ExecutionEvent, Path) -> Boolean = ::openConfigInEditor,
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  private val logger by lazy { logger<OpenMcpUserConfigHandler>() }

  override fun execute(event: ExecutionEvent): Any? {
    val target = when (val resolution = location.resolve()) {
      is McpUserConfigResolution.Unavailable -> {
        logger.info("$LOG_PREFIX ${resolution::class.simpleName} (${resolution.source})")
        notify(McpUserConfigMessages.unavailable(resolution))
        return null
      }
      is McpUserConfigResolution.Target -> resolution
    }
    try {
      service.ensureConfigFile(target.configFile)
    } catch (e: Exception) {
      // Class name only: the message carries the local path (ensureConfigFile puts it in on purpose).
      logger.error("$LOG_PREFIX could not create the config (${target.source}): ${e.javaClass.name}")
      notify(McpUserConfigMessages.CREATE_FAILED)
      return null
    }
    val failure = try {
      if (openEditor(event, target.configFile)) null else "no active workbench page"
    } catch (e: Exception) {
      // Class name only: a PartInitException message carries the path.
      e.javaClass.name
    }
    if (failure != null) {
      logger.error("$LOG_PREFIX could not open the config (${target.source}): $failure")
      notify(McpUserConfigMessages.OPEN_FAILED)
      return null
    }
    logger.info("$LOG_PREFIX opened (${target.source})")
    return null
  }

  private companion object {
    const val LOG_PREFIX = "MCP user configuration:"
  }
}

/**
 * Production default. UI thread; false when there is no window or page to open in. The file is opened
 * from disk (it normally lies outside the workspace), in the window the command was run from.
 */
private fun openConfigInEditor(event: ExecutionEvent, configFile: Path): Boolean {
  val window = HandlerUtil.getActiveWorkbenchWindow(event) ?: PlatformUI.getWorkbench().activeWorkbenchWindow
    ?: return false
  return openInActiveEditor(
    workspaceFile = { null },
    fileStore = { EFS.getLocalFileSystem().getStore(configFile.toUri()) },
    activePage = { window.activePage },
  )
}
