package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.system.Arch
import com.gitlab.eclipse.utils.system.OS
import com.gitlab.eclipse.utils.system.SystemUtils
import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.nio.file.Path
import java.util.jar.JarFile

/** Manifest header of each language server fragment naming the bundled server version (set by the build). */
internal const val LSP_VERSION_HEADER = "GitLab-Lsp-Version"

class LanguageServerInstaller internal constructor(
  /** The running platform; a seam so tests can pick one. */
  private val platform: () -> Pair<OS, Arch>,
) {
  constructor() : this({ SystemUtils.os to SystemUtils.arch })

  private val logger = logger<LanguageServerInstaller>()

  /** Returns the path of the language server binary to run, or null when it cannot be installed. */
  fun install(): String? {
    val (os, arch) = platform()
    val target = languageServerTarget(os, arch)
    if (target == null) {
      logger.error("GitLab Language Server is not bundled for ${platformLabel(os, arch)}.")
      return null
    }

    return try {
      val bundle = Platform.getBundle(target.bundleSymbolicName)
        // NOTE: This is a workaround until we figure out how to bundle binaries for all platforms with EquoIDE.
        ?: return "${System.getProperty("user.dir")}/build/gitlab-lsp/bin/${target.binaryName}"

      installFrom(bundle)?.toAbsolutePath()?.toString()
    } catch (e: Exception) {
      logger.error("Failed to install the language server: ${e.message}", e)
      null
    }
  }

  private fun installFrom(bundle: Bundle): Path? {
    val bundleFile = FileLocator.getBundleFileLocation(bundle).orElse(null) ?: run {
      logger.error("Cannot locate the file of ${bundle.symbolicName}.")
      return null
    }
    // Equinox gives a fragment its own data area (OSGi allows null here). The parent of "lsp" is
    // that area's root, where the installer before versioned installs extracted into "lsp".
    val dataRoot = bundle.getDataFile(LEGACY_DATA_FILE)?.parentFile ?: run {
      logger.error("${bundle.symbolicName} has no data area to install the language server into.")
      return null
    }

    val key = installKey(bundle.version.toString(), bundle.headers?.get(LSP_VERSION_HEADER))
    return LanguageServerExtraction(dataRoot.toPath()).install(key) { JarFile(bundleFile) }
  }

  private companion object {
    const val LEGACY_DATA_FILE = "lsp"
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
