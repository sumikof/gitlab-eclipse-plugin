package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

class LanguageServerInstaller {
  private val logger = logger<LanguageServerInstaller>()

  private val os by lazy { System.getProperty("os.name").lowercase() }
  private val arch by lazy { System.getProperty("os.arch").lowercase() }

  fun install(): String? {
    try {
      val bundle: Bundle? = Platform.getBundle("com.gitlab.eclipse.$languageServerBundle")

      val bin = if (bundle != null) {
        FileLocator.find(bundle, Path.fromOSString("/bin/gitlab-lsp"))
      } else {
        // NOTE: This is a workaround until we figure out how to bundle binaries for all platforms with EquoIDE.
        return "${System.getProperty("user.dir")}/build/gitlab-lsp/bin/$languageServerBinary"
      }

      return bin?.let { binary ->
        val destination = bundle.getDataFile("gitlab-lsp")

        if (!destination.exists()) {
          logger.info("Installing language server binary to ${destination.absolutePath}.")
          destination.createNewFile()
          destination.writeBytes(binary.readBytes())
        }

        if (!destination.canExecute()) {
          destination.setExecutable(true, false)
          logger.info("Successfully made file ${destination.absolutePath} executable.")
        }

        logger.info("Successfully installed language server in ${destination.absolutePath}.")
        return@let destination.absolutePath
      }
    } catch (e: Throwable) {
      logger.error(e.message, e)
    }

    return null
  }

  private val languageServerBundle
    get() = when {
      os.contains("windows", ignoreCase = true) -> "gitlab-language-server.win32.win32.x86_64"
      os.contains("nix|nux|aix".toRegex()) -> "gitlab-language-server.gtk.linux.x86_64"
      os.contains("mac") && arch.contains("aarch64") -> "gitlab-language-server.cocoa.macosx.aarch64"
      os.contains("mac") -> "gitlab-language-server.cocoa.macosx.x86_64"
      else -> error("Unsupported OS and architecture. os=$os, arch=$arch")
    }

  private val languageServerBinary
    get() = when {
      os.contains("windows", ignoreCase = true) -> "gitlab-lsp-win-x64.exe"
      os.contains("nix|nux|aix".toRegex()) -> "gitlab-lsp-linux-x64"
      os.contains("mac") && arch.contains("aarch64") -> "gitlab-lsp-macos-arm64"
      os.contains("mac") -> "gitlab-lsp-macos-x64"
      else -> error("Unsupported OS and architecture. os=$os, arch=$arch")
    }
}
