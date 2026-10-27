package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.debug
import com.gitlab.eclipse.utils.logger
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Written last into a finished install directory; holds the install key. */
internal const val INSTALL_MARKER = ".installed"

/** Where the fragment jar puts the server binary, relative to the install directory. */
internal const val INSTALLED_BINARY = "bin/gitlab-lsp"

internal const val UNKNOWN_LSP_VERSION = "unknown"

/** Lock file inside each install; holders of a shared lock on it are using that install. */
internal const val IN_USE_FILE = ".inuse"

private const val INSTALL_DIR_PREFIX = "lsp-"
private const val TEMP_DIR_PREFIX = "lsp.tmp-"
private const val TRASH_DIR_PREFIX = "lsp.trash-"
private const val LOCK_FILE = "lsp.lock"

/** The directory the installer before versioned installs extracted into; swept once a versioned one is in place. */
private const val LEGACY_INSTALL_DIR = "lsp"
private const val MANIFEST_DIR = "META-INF/"
private const val INSTALL_DIR_HASH_LENGTH = 12
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Identifies one install: the fragment's bundle version together with the language server version
 * it carries (`unknown` when the fragment does not declare one).
 */
internal fun installKey(bundleVersion: String, lspVersion: String?): String =
  "${bundleVersion.trim()}|${lspVersion?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_LSP_VERSION}"

/** `lsp-` followed by the first 12 hex characters of the key's sha256. */
internal fun installDirectoryName(key: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
  return INSTALL_DIR_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(INSTALL_DIR_HASH_LENGTH)
}

/**
 * Installs the language server from a fragment archive into a versioned directory under [dataRoot].
 *
 * Several Eclipse instances of one installation share the fragment's data area, so an install
 * another instance may be running from must never change:
 * - Each install key gets its own directory, never modified once complete. A new install is
 *   extracted into a temporary sibling, marked complete and moved into place, all under a
 *   cross-process lock on `lsp.lock`.
 * - An instance using an install holds a shared lock on its [IN_USE_FILE] until the JVM exits.
 * - Other installs are swept only when an exclusive lock on their in-use file shows nobody uses
 *   them, and only by renaming them aside to `lsp.trash-*` before deleting, so a directory is either
 *   kept whole or taken away whole. Anything that fails (for example a Windows binary still in use)
 *   is logged at debug level and retried on the next start.
 *
 * [renameAside] is the rename used for that; a seam so tests can make it fail as Windows does.
 *
 * Callers must serialize installs within one JVM (today the provider's lifecycle lock does). Locks
 * here are per process on POSIX: closing any channel on a locked file, as each lock probe does,
 * drops every lock this process holds on that file, so a concurrent second install in the same JVM
 * could silently release the other's install or in-use lock for other processes.
 */
internal class LanguageServerExtraction(
  private val dataRoot: Path,
  private val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
  private val lockPollInterval: Duration = DEFAULT_LOCK_POLL_INTERVAL,
  private val renameAside: (Path, Path) -> Unit = ::renameAtomically,
  private val openLockFile: (Path) -> FileChannel = ::openLockFileForWrite,
) {
  private val logger by lazy { logger<LanguageServerExtraction>() }

  /**
   * Returns the installed binary for [key], extracting it from [openArchive] first when needed, or
   * null when the install fails (the reason is logged). The archive is opened only when extracting.
   * Once [shouldAbandon] is true, it stops waiting for another instance's install lock (returning
   * null) and skips the sweep; an extraction under way still completes.
   */
  fun install(key: String, openArchive: () -> ZipFile, shouldAbandon: () -> Boolean = { false }): Path? {
    val installDir = dataRoot.resolve(installDirectoryName(key))

    if (Files.isDirectory(installDir, LinkOption.NOFOLLOW_LINKS)) {
      // Lock first, then check: a sweeper renames an install aside only while nobody holds its
      // in-use lock, so a marker read under the lock belongs to a directory that stays.
      val inUse = InUseLocks.acquire(installDir)
      if (inUse != InUseLocks.Outcome.BUSY && isComplete(installDir, key)) {
        if (inUse == InUseLocks.Outcome.UNAVAILABLE) {
          // The install itself is valid. Without the lock it is merely unprotected: another
          // instance may sweep it while this one runs from it, as before in-use locks existed.
          logger.debug("Could not take the in-use lock of $installDir; using it unprotected.")
        }
        // Sweep only if nobody else is installing right now (and locking works); never wait here.
        // Skipped while the plugin stops: the sweep is retried on the next start.
        (tryExclusiveLock(dataRoot.resolve(LOCK_FILE), openLockFile) as? LockAttempt.Acquired)
          ?.lock?.use { if (!shouldAbandon()) sweep(keep = installDir) }
        return binaryOf(installDir)
      }
      // Incomplete, or being swept by another instance: let go and install under the install lock.
      InUseLocks.release(installDir)
    }

    Files.createDirectories(dataRoot)
    val lockFile = dataRoot.resolve(LOCK_FILE)
    val attempt = pollExclusiveLock(lockFile, lockTimeout, lockPollInterval, openLockFile, shouldAbandon) {
      logger.info("Waiting for another Eclipse instance to finish installing the GitLab Language Server in $dataRoot.")
    }
    val lock = when (attempt) {
      is LockAttempt.Acquired -> attempt.lock
      is LockAttempt.Unavailable -> {
        // As 9.3.0 did, which never locked: install anyway, but never sweep without the lock.
        logger.warn(
          "The language server install lock $lockFile is unavailable on this file system " +
            "(${attempt.cause.javaClass.simpleName}: ${attempt.cause.message}); installing without it " +
            "and skipping cleanup."
        )
        null
      }
      LockAttempt.Held -> {
        logger.error("Timed out after $lockTimeout waiting for the language server install lock in $dataRoot.")
        return null
      }
      LockAttempt.Interrupted -> {
        logger.error("Interrupted while waiting for the language server install lock in $dataRoot.")
        return null
      }
      LockAttempt.Abandoned -> {
        // The plugin is stopping: not a failure, and the next start installs again.
        logger.info("Stopped waiting for the language server install lock: the plugin is stopping.")
        return null
      }
    }

    return try {
      // Another instance may have finished the same install while this one waited.
      if (isComplete(installDir, key) || extract(key, installDir, openArchive)) {
        if (InUseLocks.acquire(installDir) != InUseLocks.Outcome.HELD) {
          logger.debug("Could not take the in-use lock of $installDir; using it unprotected.")
        }
        if (lock != null && !shouldAbandon()) sweep(keep = installDir)
        binaryOf(installDir)
      } else {
        null
      }
    } finally {
      lock?.close()
    }
  }

  private fun extract(key: String, installDir: Path, openArchive: () -> ZipFile): Boolean {
    val tempDir = Files.createTempDirectory(dataRoot, TEMP_DIR_PREFIX)
    var installed = false
    try {
      logger.info("Installing the language server into $installDir.")
      openArchive().use { it.extractTo(tempDir) }

      val binary = tempDir.resolve(INSTALLED_BINARY)
      check(binary.isRegularFile()) { "The language server archive has no $INSTALLED_BINARY." }
      binary.toFile().setExecutable(true, false)
      Files.createFile(tempDir.resolve(IN_USE_FILE))
      tempDir.resolve(INSTALL_MARKER).writeText(key)

      if (isComplete(installDir, key)) {
        // Completed by another instance meanwhile (possible only without the install lock): use it.
        deleteQuietly(tempDir)
        installed = true
        return true
      }
      // A directory under this name without a valid marker is an interrupted install; replace it.
      if (Files.exists(installDir, LinkOption.NOFOLLOW_LINKS)) deleteRecursively(installDir)
      moveIntoPlace(tempDir, installDir)
      installed = true
      logger.info("Installed the language server into $installDir.")
    } catch (e: IOException) {
      logger.error("Failed to install the language server into $installDir.", e)
    } catch (e: IllegalStateException) {
      logger.error("Failed to install the language server into $installDir.", e)
    } finally {
      // Whatever went wrong, including errors not caught above, no partial extraction stays behind.
      if (!installed) deleteQuietly(tempDir)
    }
    return installed
  }

  private fun isComplete(installDir: Path, key: String): Boolean {
    val marker = installDir.resolve(INSTALL_MARKER)
    val complete = try {
      marker.isRegularFile() && marker.readText().trim() == key && installDir.resolve(INSTALLED_BINARY).isRegularFile()
    } catch (_: IOException) {
      false
    }
    return complete
  }

  private fun binaryOf(installDir: Path): Path {
    val binary = installDir.resolve(INSTALLED_BINARY)
    val file = binary.toFile()
    if (!file.canExecute()) file.setExecutable(true, false)
    return binary
  }

  /** Cleans up next to [keep]. Call only while holding the install lock. */
  private fun sweep(keep: Path) {
    val entries = try {
      Files.newDirectoryStream(dataRoot).use { it.toList() }
    } catch (e: IOException) {
      logger.debug("Could not list $dataRoot for cleanup: ${e.message}")
      return
    }
    for (entry in entries) {
      val name = entry.fileName.toString()
      when {
        // Temp dirs belong to installs that hold the install lock, which this caller has: leftovers.
        name.startsWith(TEMP_DIR_PREFIX) || name.startsWith(TRASH_DIR_PREFIX) -> deleteQuietly(entry)
        name.startsWith(INSTALL_DIR_PREFIX) && entry != keep -> sweepInstall(entry)
        name == LEGACY_INSTALL_DIR -> sweepLegacy(entry)
      }
    }
  }

  /** Removes another install unless some instance holds its in-use lock. */
  private fun sweepInstall(dir: Path) {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      // A stray file or symbolic link under an install name: remove the entry itself.
      deleteQuietly(dir)
      return
    }
    // Only one key is used per JVM run; a hold this JVM still has on another install is stale.
    InUseLocks.release(dir)
    // Held by another instance, or not lockable at all: either way it is not provably unused.
    val attempt = tryExclusiveLock(dir.resolve(IN_USE_FILE), openLockFile)
    val exclusive = (attempt as? LockAttempt.Acquired)?.lock ?: run {
      logger.debug("Keeping $dir: another instance may be using it.")
      return
    }
    moveToTrash(dir, exclusive)?.let { deleteQuietly(it) }
  }

  /**
   * The unversioned `lsp` directory of the 9.3.0 installer has no in-use file, so it is only ever
   * renamed aside, never deleted in place: on Windows a server still running from it blocks the
   * rename and the directory is kept whole.
   *
   * Residual risk on POSIX: the rename succeeds regardless, so an Eclipse instance of the previous
   * plugin still running from `data/lsp` (possible only when the fragment was updated in place while
   * it ran) loses its webviews and other files until it restarts; its running binary keeps its inode.
   */
  private fun sweepLegacy(dir: Path) {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      deleteQuietly(dir)
      return
    }
    moveToTrash(dir, exclusive = null)?.let { deleteQuietly(it) }
  }

  /**
   * Renames [dir] to a new `lsp.trash-*` and returns that path, or null when it cannot be renamed.
   *
   * The rename is first tried while still holding [exclusive]; on POSIX it succeeds, leaving no
   * moment in which another instance could start using the directory. Windows refuses to rename a
   * directory with an open handle inside, this lock's own included, so the lock is then closed and
   * the rename tried once more. An instance that opened the in-use file in that gap keeps the
   * directory (the rename fails again); one that comes after finds it gone and installs afresh.
   */
  private fun moveToTrash(dir: Path, exclusive: HeldLock?): Path? {
    val trash = dataRoot.resolve(TRASH_DIR_PREFIX + UUID.randomUUID())
    val renamedUnderLock = exclusive?.use { tryRename(dir, trash) } ?: false
    return if (renamedUnderLock || tryRename(dir, trash)) trash else null
  }

  private fun tryRename(dir: Path, trash: Path): Boolean = try {
    renameAside(dir, trash)
    true
  } catch (e: IOException) {
    logger.debug("Could not move $dir aside, will retry on the next start: ${e.message}")
    false
  }

  private fun deleteQuietly(path: Path) {
    try {
      deleteRecursively(path)
    } catch (e: IOException) {
      logger.debug("Could not delete $path, will retry on the next start: ${e.message}")
    }
  }

  companion object {
    /** Long enough for another instance to extract ~165 MB on a slow disk. */
    val DEFAULT_LOCK_TIMEOUT = 120.seconds
    val DEFAULT_LOCK_POLL_INTERVAL = 100.milliseconds
  }
}

/**
 * The shared in-use locks this JVM holds, one per install it returned. They stay open until the JVM
 * exits, which releases them. Calls for an install already held reuse the lock: a second lock on the
 * same file from this JVM would throw [OverlappingFileLockException].
 */
private object InUseLocks {
  enum class Outcome {
    /** This JVM now holds (or already held) the shared lock. */
    HELD,

    /** Another holder has an exclusive lock: a sweeper is taking the directory away. */
    BUSY,

    /** Locking is not possible here (missing directory, unsupported file system). */
    UNAVAILABLE,
  }

  private val held = HashMap<Path, HeldLock>()

  @Synchronized
  fun acquire(installDir: Path): Outcome {
    val dir = installDir.toAbsolutePath().normalize()
    if (held.containsKey(dir)) return Outcome.HELD

    val file = dir.resolve(IN_USE_FILE)
    val channel = try {
      // Created on demand for an install that predates in-use files; a shared lock needs READ.
      createIfMissing(file)
      FileChannel.open(file, StandardOpenOption.READ)
    } catch (_: IOException) {
      return Outcome.UNAVAILABLE
    }
    val lock = try {
      channel.tryLock(0, Long.MAX_VALUE, true)
    } catch (_: OverlappingFileLockException) {
      null
    } catch (_: IOException) {
      channel.close()
      return Outcome.UNAVAILABLE
    }
    if (lock == null) {
      channel.close()
      return Outcome.BUSY
    }
    held[dir] = HeldLock(channel, lock)
    return Outcome.HELD
  }

  @Synchronized
  fun release(installDir: Path) {
    held.remove(installDir.toAbsolutePath().normalize())?.close()
  }

  private fun createIfMissing(file: Path) {
    try {
      Files.createFile(file)
    } catch (_: FileAlreadyExistsException) {
      // Already there, as expected.
    }
  }
}

private fun openLockFileForWrite(file: Path): FileChannel =
  FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)

/** The outcome of trying for an exclusive lock. */
private sealed interface LockAttempt {
  /** This process now holds the lock. */
  class Acquired(val lock: HeldLock) : LockAttempt

  /** Another holder has it: another process, or another channel of this JVM. After polling: timed out. */
  data object Held : LockAttempt

  /** Locking does not work here at all (for example NFS without lockd): the probe itself failed. */
  class Unavailable(val cause: IOException) : LockAttempt

  /** The wait was interrupted; the interrupt flag is set again. */
  data object Interrupted : LockAttempt

  /** The caller stopped waiting (the plugin is stopping); no lock is held. */
  data object Abandoned : LockAttempt
}

/**
 * Tries an exclusive lock on [file] once, opening (and so creating) it with [open]. An interrupted
 * thread is reported as [LockAttempt.Interrupted]: its channel calls fail with
 * [ClosedByInterruptException], an IOException that must not read as "locking unavailable".
 */
private fun tryExclusiveLock(file: Path, open: (Path) -> FileChannel): LockAttempt {
  if (Thread.currentThread().isInterrupted) return LockAttempt.Interrupted
  val channel = try {
    open(file)
  } catch (_: ClosedByInterruptException) {
    return LockAttempt.Interrupted
  } catch (e: IOException) {
    return LockAttempt.Unavailable(e)
  }
  val lock = try {
    channel.tryLock()
  } catch (_: OverlappingFileLockException) {
    null
  } catch (_: ClosedByInterruptException) {
    channel.close()
    return LockAttempt.Interrupted
  } catch (e: IOException) {
    channel.close()
    return if (Thread.currentThread().isInterrupted) LockAttempt.Interrupted else LockAttempt.Unavailable(e)
  }
  if (lock == null) {
    channel.close()
    return LockAttempt.Held
  }
  return LockAttempt.Acquired(HeldLock(channel, lock))
}

/**
 * Polls [tryExclusiveLock] until [timeout]. Returns at once when the lock is acquired or locking is
 * unavailable; [LockAttempt.Held] means timed out. [onWait] runs once, when the first attempt finds
 * the lock held. While the lock is held, [shouldAbandon] is asked before each wait and ends the poll
 * with [LockAttempt.Abandoned] once true.
 */
private fun pollExclusiveLock(
  file: Path,
  timeout: Duration,
  interval: Duration,
  open: (Path) -> FileChannel,
  shouldAbandon: () -> Boolean,
  onWait: () -> Unit,
): LockAttempt {
  val deadline = System.nanoTime() + timeout.inWholeNanoseconds
  var waiting = false
  while (true) {
    val attempt = tryExclusiveLock(file, open)
    if (attempt !is LockAttempt.Held) return attempt
    if (!waiting) {
      waiting = true
      onWait()
    }
    if (shouldAbandon()) return LockAttempt.Abandoned
    val remaining = deadline - System.nanoTime()
    if (remaining <= 0) return LockAttempt.Held
    try {
      Thread.sleep(minOf(interval.inWholeMilliseconds, remaining / NANOS_PER_MILLI + 1))
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return LockAttempt.Interrupted
    }
  }
}

private fun ZipFile.extractTo(destination: Path) {
  val root = destination.toAbsolutePath().normalize()
  for (entry in entries()) {
    if (entry.name.startsWith(MANIFEST_DIR)) continue

    val target = root.resolve(entry.name).normalize()
    check(target.startsWith(root) && target != root) {
      "Refusing archive entry outside the install directory: ${entry.name}"
    }

    if (entry.isDirectory) {
      Files.createDirectories(target)
    } else {
      Files.createDirectories(target.parent)
      getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    }
  }
}

private fun renameAtomically(source: Path, target: Path) {
  Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
}

private fun moveIntoPlace(source: Path, target: Path) {
  try {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, target)
  }
}

/** Deletes [path] and everything under it. Symbolic links are deleted, never followed. */
private fun deleteRecursively(path: Path) {
  Files.walkFileTree(
    path,
    object : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        Files.delete(file)
        return FileVisitResult.CONTINUE
      }

      override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
        if (exc != null) throw exc
        Files.delete(dir)
        return FileVisitResult.CONTINUE
      }
    }
  )
}

private class HeldLock(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
  override fun close() {
    try {
      lock.release()
    } finally {
      channel.close()
    }
  }
}
