package com.gitlab.eclipse.diagnostics

import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file inside the exported archive. */
data class DiagnosticsEntry(val name: String, val content: String)

/**
 * The destination cannot be replaced atomically, so the export was not performed.
 *
 * Raised instead of falling back to a plain replace. A warning would not have restored the
 * guarantee: a non-atomic replace can still interleave with a concurrent export or an interrupted
 * write and leave a mangled archive, and a mangled archive is worse than none because it looks
 * usable. Failing leaves whatever was already at the destination untouched, and the user can pick a
 * destination on a filesystem that supports the rename (design §9.4.1, N6).
 */
class NonAtomicDestinationException(destination: Path) : java.io.IOException(
  "The destination filesystem does not support atomic replacement: $destination"
)

/**
 * Builds the diagnostics ZIP and puts it on disk (design §8.1, §9.4.1).
 *
 * The reference extension reaches for the `archiver` package; `java.util.zip` covers this entirely,
 * so no dependency is added (design §6.1).
 */
internal object DiagnosticsArchive {

  /**
   * The archive as bytes. Built fully in memory — the three entries are a report and two log
   * excerpts, bounded by the ring buffer's 5000 lines and the log file's 20 MB roll size.
   */
  fun build(entries: List<DiagnosticsEntry>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      zip.setLevel(Deflater.BEST_COMPRESSION)
      entries.forEach { entry ->
        zip.putNextEntry(ZipEntry(entry.name))
        zip.write(entry.content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return bytes.toByteArray()
  }

  /**
   * Writes [bytes] to [target] so that a reader only ever sees the previous file or the complete
   * new one (design §9.4.1, N6).
   *
   * Writing the bytes straight to [target] would not give that: `truncate` and `write` are separate
   * steps, so two exports racing on one path — or a crash between them — leave a mangled archive,
   * which is worse than no archive at all because it looks like one.
   *
   * The temporary file is created **in the destination directory**, because [StandardCopyOption
   * .ATOMIC_MOVE] is only meaningful within a filesystem. Two concurrent calls each get their own
   * temporary name and the kernel orders the two renames, so no application-level lock is needed.
   *
   * @throws NonAtomicDestinationException when the destination cannot be replaced atomically; the
   *   file already at [target], if any, is left exactly as it was.
   * @throws java.io.IOException when the archive could not be written; nothing is left behind.
   */
  fun writeAtomically(bytes: ByteArray, target: Path) {
    val directory = target.toAbsolutePath().parent
      ?: throw java.io.IOException("Destination has no parent directory: $target")
    val temp = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX)
    try {
      Files.write(temp, bytes)
      moveIntoPlace(temp, target)
    } finally {
      // Runs on every path. After a successful move the temporary name no longer exists, so this
      // is a no-op; after any failure it is what keeps a half-written file off the disk.
      Files.deleteIfExists(temp)
    }
  }

  private fun moveIntoPlace(temp: Path, target: Path) {
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      // No fallback on purpose — see NonAtomicDestinationException. The `finally` above removes the
      // temporary file, so the destination keeps whatever it held before this call.
      throw NonAtomicDestinationException(target)
    }
  }

  private const val TEMP_PREFIX = "gitlab-diagnostics"
  private const val TEMP_SUFFIX = ".zip.tmp"
}

/** The default file name offered in the save dialog. */
internal object DiagnosticsFileNaming {

  /**
   * `gitlab-diagnostics-<timestamp>.zip`, second resolution.
   *
   * The reference builds the same shape by punching `:` and `.` out of an ISO timestamp and cutting
   * the milliseconds; this states the resulting pattern directly. Second resolution means two
   * exports within the same second propose the same name — harmless, because the save dialog is
   * where the user settles that, and the write itself is atomic (design §9.4.1).
   */
  fun archiveName(now: Instant, zone: ZoneId): String =
    "gitlab-diagnostics-${TIMESTAMP.format(now.atZone(zone))}.zip"

  private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
}
