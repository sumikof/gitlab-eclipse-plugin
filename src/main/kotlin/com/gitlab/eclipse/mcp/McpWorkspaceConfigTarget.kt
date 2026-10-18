package com.gitlab.eclipse.mcp

import org.eclipse.core.resources.IProject
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Where the workspace-scoped MCP config goes, or why it cannot go anywhere. */
sealed interface McpWorkspaceConfigResolution {
  data class Target(val project: IProject, val configFile: Path) : McpWorkspaceConfigResolution
  data object NoProject : McpWorkspaceConfigResolution
  data class Closed(val projectName: String) : McpWorkspaceConfigResolution
  data class Unavailable(val projectName: String) : McpWorkspaceConfigResolution
}

/**
 * Decides which project holds the workspace-scoped MCP config: **the first project of [resolve]'s
 * array, which callers pass as `root.projects` — the array `workspaceFoldersOf` turns into the
 * workspace folders the language server is sent.** The active editor and the selection play no
 * part, because the server does not consult them.
 *
 * This follows the bundled language server 9.3.0 (re-check on every server update, LS-12): it reads
 * `<workspace folder>/.gitlab/duo/mcp.json` (`getMcpConfigPathCandidates`), and the folder is
 * `workspaceFolders[0]` for the MCP Dashboard and for an Agentic workflow that matches no selected
 * repository (`lib_ai_configuration_webview`, `lib_workflow_executor` `runWorkflow`). The VS Code
 * extension also uses the first folder. `root.projects` is ordered by `String.compareTo` on the
 * project name, so the first project is not always the first one the Project Explorer shows.
 *
 * There is no fallback to a later project: the server keeps reading the first folder (closed
 * projects are sent too), so a config written anywhere else would be ignored again.
 */
class McpWorkspaceConfigTarget(
  private val service: McpConfigService = McpConfigService(),
  private val isDirectory: (Path) -> Boolean = { Files.isDirectory(it) },
) {
  fun resolve(projects: Array<IProject>): McpWorkspaceConfigResolution {
    val first = projects.firstOrNull() ?: return McpWorkspaceConfigResolution.NoProject
    if (!first.isOpen) return McpWorkspaceConfigResolution.Closed(first.name)
    val directory = localDirectory(first.locationURI)
      ?: return McpWorkspaceConfigResolution.Unavailable(first.name)
    return McpWorkspaceConfigResolution.Target(first, service.getWorkspaceConfigPath(directory))
  }

  /**
   * The project's directory, when it is one the server can read: a `file:` URI that is not a UNC
   * share (the server's URI parser rejects `file:////server/share`), naming an existing directory.
   */
  private fun localDirectory(location: URI?): Path? {
    if (location == null || location.scheme != "file" || location.rawPath.orEmpty().startsWith("//")) return null
    val directory = try {
      Paths.get(location)
    } catch (_: IllegalArgumentException) {
      return null
    } catch (_: FileSystemNotFoundException) {
      return null
    }
    return directory.takeIf(isDirectory)
  }
}
