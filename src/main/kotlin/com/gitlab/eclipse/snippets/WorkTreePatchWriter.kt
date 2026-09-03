package com.gitlab.eclipse.snippets

import org.eclipse.jgit.lib.FileMode
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Outcome of [WorkTreePatchWriter.write]. */
sealed interface WriteResult {
  /** Every change landed. [writtenPaths] is what the index update must now cover. */
  data class Ok(val writtenPaths: List<String>) : WriteResult

  /**
   * The apply stopped part way and what had been written was rolled back. [notRestored] counts the
   * paths left alone because something outside Eclipse had changed them since we wrote them —
   * overwriting those would destroy the external change (A11 (b) / A16). [external] says whether
   * the abort itself was caused by an external change rather than an I/O failure.
   */
  data class RolledBack(val restored: Int, val notRestored: Int, val external: Boolean) : WriteResult
}

/**
 * Applies a planned change set to the working tree, one mediated write at a time (design §9.4-5/6/7).
 *
 * The order per path is: compare against the baseline taken when the write phase began, copy the
 * current bytes into the backup area, compare once more, then replace via an atomic rename. The gap
 * between that last comparison and the rename cannot be closed — Java has no atomic exchange — so
 * this is explicitly NOT a compare-and-swap against other processes (design §9.4.1). What it does
 * guarantee is that the content as of the final comparison is in the backup area, and that Eclipse's
 * own saves are held off by the scheduling rule the caller holds.
 *
 * On failure only the paths this class wrote are put back, and each is compared against the
 * post-image WE wrote rather than the pre-image: comparing against the pre-image would always
 * mismatch, because our own write is what changed it (design §9.4-7).
 *
 * Blocking file I/O — call from a background thread. Never lets an exception out: an I/O failure
 * becomes a rollback, because the message would quote a path (A9).
 */
class WorkTreePatchWriter {
  /**
   * Writes [changes] under [workTree], backing every pre-image up into [session].
   *
   * [afterWrite] is a test seam, called with each path right after it has been written and its
   * post-image recorded, so a test can simulate an external process landing in the window. It is a
   * no-op in production — do not remove it as "unused".
   */
  fun write(
    workTree: File,
    changes: List<PatchChange>,
    session: PatchQuarantine.Session,
    afterWrite: (String) -> Unit = {},
  ): WriteResult {
    val baseline = changes.associate { it.path to readState(workTree, it.path) }
    val written = mutableListOf<Pair<String, EntryState>>()
    for (change in changes) {
      val expected = baseline.getValue(change.path)
      try {
        if (readState(workTree, change.path) != expected) {
          return rollback(workTree, session, written, external = true)
        }
        backUp(workTree, change.path, expected, session)
        // Re-read immediately before the replace: this narrows the window to a single syscall.
        if (readState(workTree, change.path) != expected) {
          return rollback(workTree, session, written, external = true)
        }
        perform(workTree, change)
      } catch (_: Exception) {
        return rollback(workTree, session, written, external = false)
      }
      written += change.path to readState(workTree, change.path)
      afterWrite(change.path)
    }
    return WriteResult.Ok(written.map { it.first })
  }

  private fun backUp(workTree: File, path: String, state: EntryState, session: PatchQuarantine.Session) {
    when (state.kind) {
      EntryKind.ABSENT -> session.saveAbsent(path)
      // Unreadable means we cannot guarantee the backup, and the rule is never to write without
      // one. Raising here turns into a rollback in the caller's catch.
      EntryKind.UNREADABLE -> error("The current content could not be read, so it cannot be backed up.")
      EntryKind.SYMLINK -> session.saveContent(
        path,
        Files.readSymbolicLink(File(workTree, path).toPath()).toString().toByteArray(),
        executable = false,
        symlink = true,
      )
      EntryKind.REGULAR -> session.saveContent(
        path,
        File(workTree, path).readBytes(),
        executable = state.executable,
        symlink = false,
      )
    }
  }

  /** Design §9.4-6: how a path is put on disk depends on the post-image's mode. */
  private fun perform(workTree: File, change: PatchChange) {
    val target = File(workTree, change.path)
    if (change.kind == PatchChangeKind.DELETE) {
      Files.deleteIfExists(target.toPath())
      return
    }
    val content = requireNotNull(change.content) { "A non-delete change always carries content." }
    target.parentFile?.mkdirs()
    val temp = File.createTempFile(".gitlab-patch", null, target.parentFile)
    // createTempFile made a regular file; a symlink cannot be created over it.
    Files.delete(temp.toPath())
    when (change.mode) {
      FileMode.SYMLINK -> Files.createSymbolicLink(temp.toPath(), File(String(content)).toPath())
      FileMode.GITLINK -> error("Gitlink changes are rejected before the write phase.")
      else -> {
        temp.writeBytes(content)
        temp.setExecutable(change.mode == FileMode.EXECUTABLE_FILE, false)
      }
    }
    moveAtomically(temp.toPath(), target.toPath())
  }

  private fun rollback(
    workTree: File,
    session: PatchQuarantine.Session,
    written: List<Pair<String, EntryState>>,
    external: Boolean,
  ): WriteResult {
    var restored = 0
    var notRestored = 0
    for ((path, postImage) in written.asReversed()) {
      // Compare against what WE wrote: anything else means an external process got there first,
      // and restoring would overwrite its change (design §9.4-7).
      if (readState(workTree, path) != postImage) {
        notRestored++
        continue
      }
      if (session.restore(path, workTree)) restored++ else notRestored++
    }
    return WriteResult.RolledBack(restored, notRestored, external)
  }

  private fun moveAtomically(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  /**
   * What the working tree holds for [path] right now. Symlinks are inspected without following
   * them: following would read the target's content, misreport the kind, and throw on a link
   * pointing nowhere.
   */
  private fun readState(workTree: File, path: String): EntryState {
    val file = File(workTree, path).toPath()
    return try {
      when {
        Files.isSymbolicLink(file) -> EntryState(
          EntryKind.SYMLINK,
          digest(Files.readSymbolicLink(file).toString().toByteArray()),
          executable = false,
          lastModified = modifiedAt(file),
        )
        !Files.exists(file, LinkOption.NOFOLLOW_LINKS) -> EntryState(EntryKind.ABSENT, null, false, null)
        else -> EntryState(
          EntryKind.REGULAR,
          digest(Files.readAllBytes(file)),
          executable = Files.isExecutable(file),
          lastModified = modifiedAt(file),
        )
      }
    } catch (_: Exception) {
      // Unreadable is not "absent": a distinct state keeps the comparison from silently passing.
      EntryState(EntryKind.UNREADABLE, null, false, null)
    }
  }

  private fun modifiedAt(file: Path): Long =
    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis()

  private fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private enum class EntryKind { ABSENT, REGULAR, SYMLINK, UNREADABLE }

  private data class EntryState(
    val kind: EntryKind,
    val sha256: String?,
    val executable: Boolean,
    val lastModified: Long?,
  )
}
