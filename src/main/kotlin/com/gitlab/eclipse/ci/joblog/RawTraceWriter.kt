package com.gitlab.eclipse.ci.joblog

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes a raw trace so that [target] is either untouched or complete, never half written. */
object RawTraceWriter {
  /**
   * Lets [copy] fill a temporary file next to [target], then moves it over [target] (atomically when the file
   * system can). The temporary file is removed on every failure. Messages created here never contain the path.
   */
  fun write(target: Path, copy: (OutputStream) -> Unit) {
    val directory = target.parent ?: throw IOException("no parent directory")
    val temporary = Files.createTempFile(directory, ".job-", ".log.part")
    try {
      Files.newOutputStream(temporary).use(copy)
      moveOver(temporary, target)
    } finally {
      try {
        Files.deleteIfExists(temporary)
      } catch (ignored: IOException) {
        // Nothing more can be done; the original failure (if any) is what matters.
      }
    }
  }

  private fun moveOver(source: Path, target: Path) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (ignored: AtomicMoveNotSupportedException) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
