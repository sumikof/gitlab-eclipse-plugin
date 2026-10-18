package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.utils.system.OS
import com.gitlab.eclipse.utils.system.SystemUtils
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The environment value the user-scoped MCP config location comes from. */
enum class McpUserConfigSource { APPDATA, XDG_CONFIG_HOME, HOME }

/** Step 1 of [McpUserConfigLocation]: which value the language server would use, as plain strings. */
sealed interface McpUserConfigSelection {
  data class Chosen(val source: McpUserConfigSource, val value: String, val segments: List<String>) :
    McpUserConfigSelection
}

/** Where the user-scoped MCP config is, or why there is none the language server would read. */
sealed interface McpUserConfigResolution {
  /** The server reads [configFile], which comes from [source]. */
  data class Target(val configFile: Path, val source: McpUserConfigSource) : McpUserConfigResolution

  /** Nothing may be created or opened, and the user is told why. */
  sealed interface Unavailable : McpUserConfigResolution {
    val source: McpUserConfigSource
  }

  /** [source] is not set, so the language server has no user-scoped config at all. */
  data class Missing(override val source: McpUserConfigSource) : Unavailable, McpUserConfigSelection

  /** [source] is not an absolute path: the server would resolve it against its working directory. */
  data class NotAbsolute(override val source: McpUserConfigSource) : Unavailable

  /** [source] is not a path this OS accepts. */
  data class Invalid(override val source: McpUserConfigSource) : Unavailable
}

/**
 * Where the bundled language server reads the user-scoped MCP config: **LS 9.30.0
 * `getDuoConfigFilePath("mcp.json")`** (`packages/core/ai_configuration`, region `src/utils/paths.ts`,
 * `getDuoConfigDir`, the same rule as 9.3.0's `packages/lib_ai_configuration`; the MCP manager, the MCP
 * Dashboard and the knowledge graph all use it). Re-check this rule on every server update (LS-12):
 *
 * - `process.platform === "win32"`: `APPDATA` (no user config when it is unset or empty) joined with
 *   `GitLab/duo`. `XDG_CONFIG_HOME` is not consulted.
 * - Otherwise, macOS included: `XDG_CONFIG_HOME` joined with `gitlab/duo` when it is set and not blank to
 *   `trim()` (the value itself is used untrimmed), else `os.homedir()` joined with `.gitlab/duo`.
 *
 * `os.homedir()` returns `HOME` whenever it is set, even empty, and only then the passwd entry, while the
 * JDK's `user.home` is the passwd entry whatever `HOME` says — so `HOME` comes first here and `user.home`
 * stands in for the passwd fallback. The server inherits this JVM's environment
 * (`GitLabLanguageServerProcessProvider`), so both see the same values.
 *
 * The VS Code extension still opens `~/.gitlab/duo/mcp.json` everywhere; this follows the server, which is
 * what reads the file. Deliberately separate from the workspace-scoped rule (`McpWorkspaceConfigTarget`).
 */
class McpUserConfigLocation(
  private val isWindows: Boolean = SystemUtils.os == OS.WINDOWS,
  private val env: (String) -> String? = System::getenv,
  private val userHome: () -> String? = { System.getProperty("user.home") },
) {
  fun resolve(): McpUserConfigResolution {
    val chosen = when (val selection = select()) {
      is McpUserConfigResolution.Missing -> return selection
      is McpUserConfigSelection.Chosen -> selection
    }
    val base = try {
      Path.of(chosen.value)
    } catch (_: InvalidPathException) {
      return McpUserConfigResolution.Invalid(chosen.source)
    }
    if (!base.isAbsolute) return McpUserConfigResolution.NotAbsolute(chosen.source)
    val configFile = chosen.segments.fold(base) { path, segment -> path.resolve(segment) }.normalize()
    return McpUserConfigResolution.Target(configFile, chosen.source)
  }

  /** Step 1: the value the server joins and the segments it appends, as plain strings (any OS can test it). */
  internal fun select(): McpUserConfigSelection {
    if (!isWindows) {
      val xdgConfigHome = env("XDG_CONFIG_HOME")
      if (xdgConfigHome != null && !xdgConfigHome.all(::isJsWhiteSpace)) {
        return McpUserConfigSelection.Chosen(McpUserConfigSource.XDG_CONFIG_HOME, xdgConfigHome, XDG_SEGMENTS)
      }
      val home = env("HOME") ?: userHome()?.takeUnless { it.isEmpty() || it == "?" }
        ?: return McpUserConfigResolution.Missing(McpUserConfigSource.HOME)
      return McpUserConfigSelection.Chosen(McpUserConfigSource.HOME, home, HOME_SEGMENTS)
    }
    val appData = env("APPDATA")
    if (appData.isNullOrEmpty()) return McpUserConfigResolution.Missing(McpUserConfigSource.APPDATA)
    return McpUserConfigSelection.Chosen(McpUserConfigSource.APPDATA, appData, WINDOWS_SEGMENTS)
  }

  private companion object {
    val WINDOWS_SEGMENTS = listOf("GitLab", "duo", "mcp.json")
    val XDG_SEGMENTS = listOf("gitlab", "duo", "mcp.json")
    val HOME_SEGMENTS = listOf(".gitlab", "duo", "mcp.json")
  }
}

/** JavaScript's `String.prototype.trim()` set: WhiteSpace (with every Zs character) and LineTerminator. */
private fun isJsWhiteSpace(c: Char): Boolean =
  c in "\t\u000B\u000C\uFEFF\n\r\u2028\u2029" || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
