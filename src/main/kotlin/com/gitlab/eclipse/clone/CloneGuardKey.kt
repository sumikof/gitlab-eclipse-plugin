package com.gitlab.eclipse.clone

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the [com.gitlab.eclipse.mergerequests.GitOperationGuard] key that serializes clones by
 * filesystem location.
 *
 * The guard compares keys by string equality only, so `toAbsolutePath().normalize()` is not
 * enough: a symlinked parent and the real parent, or two spellings that differ only in case on a
 * case-insensitive filesystem, would take two separate guards for one directory and let two
 * clones initialize the same work tree.
 *
 * The clone target itself may not exist yet (and must not be pre-created), so `toRealPath()` is
 * applied to the nearest ancestor that does exist and the remaining segments are appended. The
 * WHOLE key is then lowercased — keeping the ancestor's original case would make
 * `<tmp>/Foo` hash differently before and after the directory is created, which is exactly the
 * window in which a second clone still passes the entry check.
 *
 * Lowercasing deliberately over-merges: on a case-sensitive filesystem `Foo` and `foo` really are
 * different directories, and folding them costs only a spurious "a clone is already running
 * there" refusal, whereas under-merging corrupts a repository.
 */
object CloneGuardKey {

  fun of(destination: File): String {
    var current: Path = destination.toPath().toAbsolutePath().normalize()
    val missing = ArrayDeque<String>()
    while (!Files.exists(current)) {
      val name = current.fileName
      val parent = current.parent
      if (name == null || parent == null) break // the filesystem root: nothing left to walk up to
      missing.addFirst(name.toString())
      current = parent
    }
    val existingPart = realPathOrSelf(current)
    val key = if (missing.isEmpty()) {
      existingPart
    } else {
      existingPart + File.separator + missing.joinToString(File.separator)
    }
    return key.lowercase()
  }

  /** A racing delete can make an ancestor vanish between the check and the resolve. */
  private fun realPathOrSelf(path: Path): String =
    try {
      path.toRealPath().toString()
    } catch (_: IOException) {
      path.toString()
    }
}
