package com.gitlab.eclipse.snippets

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The backup area a patch application writes its pre-images into (design §9.4.1 / §9.4.2).
 *
 * The window between the final comparison and the actual replace cannot be closed from Java —
 * there is no atomic exchange — so recoverability, not exclusion, is what protects the user: every
 * path this plugin is about to overwrite or delete is copied here first. One apply is one session
 * folder; a session with no `COMPLETE` marker is the residue of a crashed apply and is deliberately
 * kept until it expires so the content stays recoverable.
 *
 * Lifetime and caps are the values fixed as U8 in the implementation plan: a session inside
 * [MIN_RETENTION] is never deleted, and the area is bounded by BOTH a byte cap and an entry count,
 * whichever is reached first. When room cannot be made without deleting a session that is still
 * inside the retention window, the apply is refused rather than the backup sacrificed.
 *
 * Blocking file I/O — call from a background thread. Never logs a path (A9): callers report counts.
 */
class PatchQuarantine(
  private val root: File = defaultRoot(),
  private val clock: () -> Instant = Instant::now,
) {
  sealed interface Opened {
    data class Ok(val session: Session, val prunedCount: Int) : Opened

    /** Room could not be made without deleting a session inside the retention window (A21 (b)). */
    data object OutOfSpace : Opened
  }

  /**
   * Opens a session sized for [estimatedBytes] of pre-images, pruning expired sessions oldest
   * first to make room. Returns [Opened.Ok] with the number of sessions it deleted so the caller
   * can disclose the deletion (A21), or [Opened.OutOfSpace] when the apply must not start.
   */
  fun open(estimatedBytes: Long): Opened {
    // A single apply larger than the whole area can never be backed up: do not begin writing.
    if (estimatedBytes > MAX_TOTAL_BYTES) return Opened.OutOfSpace
    if (!root.isDirectory && !root.mkdirs()) return Opened.OutOfSpace

    var pruned = 0
    val now = clock()
    while (!fits(estimatedBytes)) {
      val expired = sessionDirs()
        .filter { isExpired(it, now) }
        .minByOrNull { startedAt(it) ?: Long.MAX_VALUE }
        ?: return Opened.OutOfSpace
      if (!expired.deleteRecursively()) return Opened.OutOfSpace
      pruned++
    }

    val dir = File(root, "${now.toEpochMilli()}-${counter.incrementAndGet()}")
    if (!dir.mkdirs()) return Opened.OutOfSpace
    return Opened.Ok(Session(dir), pruned)
  }

  /**
   * Startup cleanup (A21): deletes every session past the retention window and returns how many
   * went. Sessions inside the window are kept whether or not they completed — an unfinished one is
   * exactly the case where the user may still need to recover from it.
   */
  fun sweep(): Int {
    if (!root.isDirectory) return 0
    val now = clock()
    return sessionDirs().count { dir -> isExpired(dir, now) && dir.deleteRecursively() }
  }

  private fun fits(estimatedBytes: Long): Boolean {
    val dirs = sessionDirs()
    if (dirs.size + 1 > MAX_ENTRIES) return false
    return usedBytes(dirs) + estimatedBytes <= MAX_TOTAL_BYTES
  }

  private fun sessionDirs(): List<File> =
    root.listFiles()?.filter { it.isDirectory && startedAt(it) != null }.orEmpty()

  private fun usedBytes(dirs: List<File>): Long =
    dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

  /** The session's start time is its folder name, not its mtime: writing pre-images moves mtime. */
  private fun startedAt(dir: File): Long? = dir.name.substringBefore('-').toLongOrNull()

  private fun isExpired(dir: File, now: Instant): Boolean {
    val started = startedAt(dir) ?: return false
    return Instant.ofEpochMilli(started).plus(MIN_RETENTION).isBefore(now)
  }

  /** One patch application's pre-images. Not thread safe: a single apply writes it serially. */
  class Session(private val dir: File) {
    private val index = AtomicInteger()

    /** Folder name of this session, relative to the quarantine root. */
    val id: String get() = dir.name

    /** Records the content [path] held at the final comparison, before it is overwritten (A18). */
    fun saveContent(path: String, bytes: ByteArray, executable: Boolean, symlink: Boolean) {
      val entry = newEntry(path, if (symlink) KIND_SYMLINK else KIND_REGULAR, executable)
      File(entry, CONTENT_FILE).writeBytes(bytes)
    }

    /** Records that [path] did not exist, so a rollback removes whatever was created there. */
    fun saveAbsent(path: String) {
      newEntry(path, KIND_ABSENT, executable = false)
    }

    /**
     * Puts [path] back the way it was recorded, under [workTree]. Returns false when this session
     * holds no record for the path or the restore itself failed — the caller counts that as
     * "not restored" rather than treating it as success.
     */
    fun restore(path: String, workTree: File): Boolean {
      val entry = entries().firstOrNull { readMeta(it)[META_PATH] == path } ?: return false
      val meta = readMeta(entry)
      val target = File(workTree, path)
      return try {
        when (meta[META_KIND]) {
          KIND_ABSENT -> {
            Files.deleteIfExists(target.toPath())
            true
          }
          KIND_SYMLINK -> {
            val link = File(entry, CONTENT_FILE).readText()
            Files.deleteIfExists(target.toPath())
            target.parentFile?.mkdirs()
            Files.createSymbolicLink(target.toPath(), File(link).toPath())
            true
          }
          KIND_REGULAR -> {
            target.parentFile?.mkdirs()
            val temp = File.createTempFile(".gitlab-restore", null, target.parentFile)
            temp.writeBytes(File(entry, CONTENT_FILE).readBytes())
            temp.setExecutable(meta[META_EXEC] == "true", false)
            Files.move(
              temp.toPath(),
              target.toPath(),
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE,
            )
            true
          }
          else -> false
        }
      } catch (_: Exception) {
        // Restoring is already the failure path; the exception message would quote a path (A9).
        false
      }
    }

    /** Marks the apply as finished. Its absence is what identifies a crashed apply's residue. */
    fun complete() {
      File(dir, COMPLETE_MARKER).writeText("")
    }

    private fun newEntry(path: String, kind: String, executable: Boolean): File {
      val entry = File(File(dir, ENTRIES_DIR), index.incrementAndGet().toString())
      entry.mkdirs()
      // The path lives inside the backup area because a restore needs it. A9 governs logs and
      // notifications, which only ever carry counts.
      File(entry, META_FILE).writeText("$META_PATH=$path\n$META_KIND=$kind\n$META_EXEC=$executable\n")
      return entry
    }

    private fun entries(): List<File> = File(dir, ENTRIES_DIR).listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun readMeta(entry: File): Map<String, String> =
      File(entry, META_FILE).takeIf { it.isFile }
        ?.readLines()
        ?.mapNotNull { line ->
          val separator = line.indexOf('=')
          if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        ?.toMap()
        .orEmpty()
  }

  companion object {
    /** U8: a session younger than this is never deleted to make room. */
    val MIN_RETENTION: Duration = Duration.ofDays(7)

    /** U8: byte cap on the whole area. */
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /** U8: entry cap on the whole area, checked alongside [MAX_TOTAL_BYTES]. */
    const val MAX_ENTRIES = 50

    private const val ENTRIES_DIR = "entries"
    private const val COMPLETE_MARKER = "COMPLETE"
    private const val CONTENT_FILE = "content"
    private const val META_FILE = "meta"
    private const val META_PATH = "path"
    private const val META_KIND = "kind"
    private const val META_EXEC = "exec"
    private const val KIND_REGULAR = "REGULAR"
    private const val KIND_SYMLINK = "SYMLINK"
    private const val KIND_ABSENT = "ABSENT"

    /** Under the bundle state location, which [com.gitlab.eclipse.GitLabEclipseStartup] publishes. */
    private fun defaultRoot(): File {
      val stateDir = System.getProperty("gitlab.plugin.state.dir") ?: System.getProperty("java.io.tmpdir")
      return File(stateDir, "patch-backups")
    }

    private val counter = AtomicInteger()
  }
}
