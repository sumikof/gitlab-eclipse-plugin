package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.debug
import com.gitlab.eclipse.utils.logger
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
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

private const val INSTALL_DIR_PREFIX = "lsp-"
private const val TEMP_DIR_PREFIX = "lsp.tmp-"
private const val LOCK_FILE = "lsp.lock"

/** The directory the installer before versioned installs extracted into; swept once a versioned one is in place. */
private const val LEGACY_INSTALL_DIR = "lsp"
private const val MANIFEST_DIR = "META-INF/"
private const val INSTALL_DIR_HASH_LENGTH = 12

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
 * Each install key gets its own directory, which is never modified once complete: several Eclipse
 * instances of one installation share the fragment's data area, and a server already running from
 * an install keeps its files. A new install is extracted into a temporary sibling, marked complete
 * and then moved into place, all under a cross-process lock on `lsp.lock`. Afterwards other
 * installs, temporary leftovers of interrupted extractions and the legacy unversioned `lsp`
 * directory are swept on a best-effort basis: a deletion that fails (for example a Windows binary
 * still in use by another instance) is logged at debug level and retried on the next start.
 */
internal class LanguageServerExtraction(
  private val dataRoot: Path,
  private val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
  private val lockPollInterval: Duration = DEFAULT_LOCK_POLL_INTERVAL,
) {
  private val logger by lazy { logger<LanguageServerExtraction>() }

  /**
   * Returns the installed binary for [key], extracting it from [openArchive] first when needed, or
   * null when the install fails (the reason is logged). The archive is opened only when extracting.
   */
  fun install(key: String, openArchive: () -> ZipFile): Path? {
    val installDir = dataRoot.resolve(installDirectoryName(key))

    if (isComplete(installDir, key)) {
      // Fast path: no waiting. Sweep only if nobody else is installing right now.
      tryLockOnce()?.use { sweep(keep = installDir) }
      return binaryOf(installDir)
    }

    Files.createDirectories(dataRoot)
    val lock = acquireLock() ?: run {
      logger.error("Timed out after $lockTimeout waiting for the language server install lock in $dataRoot.")
      return null
    }

    return lock.use {
      // Another instance may have finished the same install while this one waited.
      if (isComplete(installDir, key) || extract(key, installDir, openArchive)) {
        sweep(keep = installDir)
        binaryOf(installDir)
      } else {
        null
      }
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
      tempDir.resolve(INSTALL_MARKER).writeText(key)

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

  /** Deletes temp leftovers, other installs and the legacy directory. Call only while holding the lock. */
  private fun sweep(keep: Path) {
    val stale = try {
      Files.newDirectoryStream(dataRoot).use { entries ->
        entries.filter { it != keep && isSweepable(it.fileName.toString()) }
      }
    } catch (e: IOException) {
      logger.debug("Could not list $dataRoot for cleanup: ${e.message}")
      return
    }
    stale.forEach { deleteQuietly(it) }
  }

  private fun isSweepable(name: String) =
    name == LEGACY_INSTALL_DIR || name.startsWith(TEMP_DIR_PREFIX) || name.startsWith(INSTALL_DIR_PREFIX)

  private fun deleteQuietly(path: Path) {
    try {
      deleteRecursively(path)
    } catch (e: IOException) {
      logger.debug("Could not delete $path, will retry on the next start: ${e.message}")
    }
  }

  /** Tries the lock once; null when another holder (in this or another process) has it. */
  private fun tryLockOnce(): HeldLock? {
    val channel = try {
      FileChannel.open(dataRoot.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    } catch (e: IOException) {
      logger.debug("Could not open the install lock in $dataRoot: ${e.message}")
      return null
    }
    val lock = try {
      channel.tryLock()
    } catch (_: OverlappingFileLockException) {
      // Held by another channel of this JVM.
      null
    } catch (e: IOException) {
      logger.debug("Could not take the install lock in $dataRoot: ${e.message}")
      null
    }
    if (lock == null) {
      channel.close()
      return null
    }
    return HeldLock(channel, lock)
  }

  /** Polls for the lock until [lockTimeout]; null on timeout. */
  private fun acquireLock(): HeldLock? {
    val deadline = System.nanoTime() + lockTimeout.inWholeNanoseconds
    while (true) {
      tryLockOnce()?.let { return it }
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) return null
      try {
        Thread.sleep(minOf(lockPollInterval.inWholeMilliseconds, remaining / NANOS_PER_MILLI + 1))
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
      }
    }
  }

  companion object {
    /** Long enough for another instance to extract ~165 MB on a slow disk. */
    val DEFAULT_LOCK_TIMEOUT = 120.seconds
    val DEFAULT_LOCK_POLL_INTERVAL = 100.milliseconds
    private const val NANOS_PER_MILLI = 1_000_000L
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
