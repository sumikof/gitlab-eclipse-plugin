package com.gitlab.eclipse.ci.joblog

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

private const val COPY_BUFFER_BYTES = 64 * 1024

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

  /**
   * Copies [input] to [out], asking [isCancelled] before every read and once more at the end. When it says yes the
   * copy stops with [CancellationException], so [write] removes the temporary file and leaves the target as it was
   * (review M-4: closing the editor or stopping the plugin must not keep a download running).
   */
  fun copy(input: InputStream, out: OutputStream, isCancelled: () -> Boolean) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    while (true) {
      if (isCancelled()) throw CancellationException("Raw job log download cancelled")
      val read = input.read(buffer)
      if (read < 0) break
      out.write(buffer, 0, read)
    }
    if (isCancelled()) throw CancellationException("Raw job log download cancelled")
  }

  private fun moveOver(source: Path, target: Path) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (ignored: AtomicMoveNotSupportedException) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
