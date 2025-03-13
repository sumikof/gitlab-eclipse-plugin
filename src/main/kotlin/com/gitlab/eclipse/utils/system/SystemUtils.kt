package com.gitlab.eclipse.utils.system

object SystemUtils {
  val os
    get() = let {
      val osName = System.getProperty("os.name").lowercase()

      when {
        osName.contains("mac") || osName.contains("darwin") -> OS.MAC
        osName.contains("win") -> OS.WINDOWS
        osName.contains("nix|nux|aix".toRegex()) -> OS.LINUX
        else -> OS.UNKNOWN
      }
    }

  val arch
    get() = let {
      when {
        System.getProperty("os.arch").lowercase().contains("aarch64") -> Arch.ARM64
        else -> Arch.X86_64
      }
    }

  fun isWindows() = os == OS.WINDOWS
}

enum class Arch {
  X86_64,
  ARM64,
}

enum class OS {
  WINDOWS,
  MAC,
  LINUX,
  UNKNOWN
}
