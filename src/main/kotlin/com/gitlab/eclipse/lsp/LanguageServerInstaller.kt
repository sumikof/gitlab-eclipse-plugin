package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.system.Arch
import com.gitlab.eclipse.utils.system.OS
import com.gitlab.eclipse.utils.system.SystemUtils
import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarFile
import kotlin.sequences.forEach

@Suppress("NestedBlockDepth")
class LanguageServerInstaller {
  private val logger = logger<LanguageServerInstaller>()

  fun install(): String? {
    try {
      val bundle: Bundle? = Platform.getBundle("com.gitlab.eclipse.$languageServerBundle")

      val bundleJar = if (bundle != null) {
        FileLocator.getBundleFileLocation(bundle).map { JarFile(it) }.orElse(null)
      } else {
        // NOTE: This is a workaround until we figure out how to bundle binaries for all platforms with EquoIDE.
        return "${System.getProperty("user.dir")}/build/gitlab-lsp/bin/$languageServerBinary"
      }

      return bundleJar?.let { jar ->
        val destination = bundle.getDataFile("lsp")

        if (!destination.exists()) {
          logger.info("Installing language server binary to ${destination.absolutePath}.")

          destination.mkdir()
          jar.use { it.extractTo(destination) }
        }

        val lspBinary = destination.resolve("bin/gitlab-lsp")
        if (!lspBinary.canExecute()) {
          lspBinary.setExecutable(true, false)
          logger.info("Successfully made file ${lspBinary.absolutePath} executable.")
        }

        logger.info("Successfully installed language server binaries in ${destination.absolutePath}.")
        return@let lspBinary.absolutePath
      }
    } catch (e: Throwable) {
      logger.error(e.message, e)
    }

    return null
  }

  @Suppress("NestedBlockDepth")
  private fun JarFile.extractTo(destination: File) {
    entries().asSequence().forEach { entry ->
      val file = File(destination, entry.name)

      if (entry.isDirectory) {
        file.mkdirs()
      } else {
        file.parentFile?.mkdirs()

        getInputStream(entry).use { input ->
          FileOutputStream(file).use { output ->
            input.copyTo(output)
          }
        }
      }
    }
  }

  private val languageServerBundle
    get() = when {
      SystemUtils.os == OS.WINDOWS -> "gitlab-language-server.win32.win32.x86_64"
      SystemUtils.os == OS.LINUX -> "gitlab-language-server.gtk.linux.x86_64"
      SystemUtils.os == OS.MAC && SystemUtils.arch == Arch.ARM64 -> "gitlab-language-server.cocoa.macosx.aarch64"
      SystemUtils.os == OS.MAC -> "gitlab-language-server.cocoa.macosx.x86_64"
      else -> error("Unsupported OS and architecture. os=${SystemUtils.os}, arch=${SystemUtils.arch}")
    }

  private val languageServerBinary
    get() = when {
      SystemUtils.os == OS.WINDOWS -> "gitlab-lsp-win-x64.exe"
      SystemUtils.os == OS.LINUX -> "gitlab-lsp-linux-x64"
      SystemUtils.os == OS.MAC && SystemUtils.arch == Arch.ARM64 -> "gitlab-lsp-macos-arm64"
      SystemUtils.os == OS.MAC -> "gitlab-lsp-macos-x64"
      else -> error("Unsupported OS and architecture. os=${SystemUtils.os}, arch=${SystemUtils.arch}")
    }
}
