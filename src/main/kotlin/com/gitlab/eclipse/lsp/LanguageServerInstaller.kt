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
    val os = SystemUtils.os
    val arch = SystemUtils.arch
    val target = languageServerTarget(os, arch)
    if (target == null) {
      logger.error("GitLab Language Server is not bundled for ${platformLabel(os, arch)}.")
      return null
    }

    try {
      val bundle: Bundle? = Platform.getBundle(target.bundleSymbolicName)

      val bundleJar = if (bundle != null) {
        FileLocator.getBundleFileLocation(bundle).map { JarFile(it) }.orElse(null)
      } else {
        // NOTE: This is a workaround until we figure out how to bundle binaries for all platforms with EquoIDE.
        return "${System.getProperty("user.dir")}/build/gitlab-lsp/bin/${target.binaryName}"
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
}

/** The fragment bundle that carries the language server for a platform, and the binary's name inside it. */
internal data class LanguageServerTarget(val bundleSymbolicName: String, val binaryName: String)

/**
 * Maps a platform to its language server fragment, or null when no fragment is built for it.
 * Linux on ARM64 has a server binary upstream but no Eclipse fragment, so it is reported as
 * unsupported rather than resolved to the x86_64 fragment, whose binary it cannot run.
 */
internal fun languageServerTarget(os: OS, arch: Arch): LanguageServerTarget? = when (os) {
  OS.WINDOWS -> when (arch) {
    Arch.ARM64 -> target("win32.win32.aarch64", "gitlab-lsp-win-arm64.exe")
    Arch.X86_64 -> target("win32.win32.x86_64", "gitlab-lsp-win-x64.exe")
  }
  OS.MAC -> when (arch) {
    Arch.ARM64 -> target("cocoa.macosx.aarch64", "gitlab-lsp-macos-arm64")
    Arch.X86_64 -> target("cocoa.macosx.x86_64", "gitlab-lsp-macos-x64")
  }
  OS.LINUX -> when (arch) {
    Arch.ARM64 -> null
    Arch.X86_64 -> target("gtk.linux.x86_64", "gitlab-lsp-linux-x64")
  }
  OS.UNKNOWN -> null
}

/** The platform as the logs name it, e.g. `linux/aarch64`. */
internal fun platformLabel(os: OS, arch: Arch): String {
  val archLabel = when (arch) {
    Arch.ARM64 -> "aarch64"
    Arch.X86_64 -> "x86_64"
  }
  return "${os.name.lowercase()}/$archLabel"
}

private fun target(platform: String, binaryName: String) =
  LanguageServerTarget("com.gitlab.eclipse.gitlab-language-server.$platform", binaryName)
