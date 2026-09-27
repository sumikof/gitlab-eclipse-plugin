package com.gitlab.eclipse.codesuggestions.tutorial

import java.nio.file.Files
import java.nio.file.Path

/**
 * The three directory operations [DuoTutorialWorkspaceWriter] needs, behind a seam so a headless
 * spec can record every path the writer touches and inject a reservation failure (design §9.2).
 */
interface DuoTutorialFileSystem {
  /** `mkdir -p`: the parent `duo-tutorial` directory, which may already exist. */
  fun createDirectories(path: Path)

  /** Creates exactly one new directory; fails if it already exists. This is the reservation. */
  fun createDirectory(path: Path)

  /** Removes [path] with everything under it; a missing path is not an error. */
  fun deleteRecursively(path: Path)

  object Default : DuoTutorialFileSystem {
    override fun createDirectories(path: Path) {
      Files.createDirectories(path)
    }

    override fun createDirectory(path: Path) {
      Files.createDirectory(path)
    }

    override fun deleteRecursively(path: Path) {
      if (!Files.exists(path)) return
      Files.walk(path).use { entries ->
        entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
      }
    }
  }
}
