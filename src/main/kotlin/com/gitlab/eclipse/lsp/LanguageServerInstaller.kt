package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

class LanguageServerInstaller {
  private val logger = logger<LanguageServerInstaller>()

  // TODO: Allow to load platform dependent binaries.
  fun install(): String? {
    try {
      val bundle: Bundle? = Platform.getBundle("com.gitlab.eclipse.gitlab-language-server.cocoa.macosx.aarch64")

      val bin = if (bundle != null) {
        FileLocator.find(bundle, Path.fromOSString("/bin/gitlab-lsp"))
      } else {
        // NOTE: This is a workaround until we figure out how to bundle binaries for all platforms with EquoIDE.
        return "${System.getProperty("user.dir")}/build/gitlab-lsp/bin/gitlab-lsp-macos-arm64"
      }

      return bin?.let { binary ->
        val destination = bundle.getDataFile("gitlab-lsp")
        if (destination.createNewFile()) {
          destination.writeText(binary.readText())
        } else {
          logger.warn("Unable to create language server binary in data path")
        }
        destination.setExecutable(true)
        logger.info("Successfully installed language server from package")
        return@let destination.absolutePath
      }
    } catch (e: Throwable) {
      logger.error(e.message, e)
    }

    return null
  }
}
