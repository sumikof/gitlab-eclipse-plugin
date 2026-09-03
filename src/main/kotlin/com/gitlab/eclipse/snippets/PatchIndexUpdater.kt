package com.gitlab.eclipse.snippets

import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Outcome of [PatchIndexUpdater.update]. */
sealed interface IndexUpdateResult {
  data object Ok : IndexUpdateResult

  /** The index no longer matched HEAD when the lock was taken; it was left exactly as found. */
  data object Conflicted : IndexUpdateResult
}

/**
 * Updates the index after a patch has already landed in the working tree (design §9.4-5-8 / §12.2).
 *
 * JGit is never allowed to write the index here. Its working-tree PatchApplier locks the dir-cache
 * and commits it even when it collected errors, and putting that back afterwards would overwrite
 * any `git add` that arrived in between. Doing the update ourselves removes that race structurally:
 * the only window left is between taking the lock and writing, and the state is re-checked inside it.
 *
 * The comparison against HEAD is also used before anything is written, so a staged change aborts
 * the apply instead of being silently discarded (A19).
 *
 * Blocking local I/O — call from a background thread.
 */
class PatchIndexUpdater {
  /**
   * Stages [changes] for [repo], reading length and timestamps from the files already written under
   * [workTree]. Returns [IndexUpdateResult.Conflicted] without writing anything when the index no
   * longer matches [headTreeId] — the same baseline the plan was built on.
   */
  fun update(
    repo: Repository,
    workTree: File,
    changes: List<PatchChange>,
    headTreeId: ObjectId,
  ): IndexUpdateResult {
    // The blobs must exist in the object database before the index can point at them; flushing
    // only now means an apply that never got this far left the object database untouched.
    val blobIds = repo.newObjectInserter().use { inserter ->
      val ids = changes.associate { change ->
        change.path to change.content?.let { inserter.insert(Constants.OBJ_BLOB, it) }
      }
      inserter.flush()
      ids
    }

    val index = DirCache.lock(repo, null)
    try {
      // Re-checked under the lock: the working-tree writes have already happened, so an external
      // `git add` had a whole window in which to land (design §9.4-5-8).
      if (countIndexEntriesDifferingFromHead(repo, changes.map { it.path }, headTreeId) > 0) {
        return IndexUpdateResult.Conflicted
      }
      val editor = index.editor()
      for (change in changes) {
        if (change.kind == PatchChangeKind.DELETE) {
          editor.add(DirCacheEditor.DeletePath(change.path))
        } else {
          editor.add(entryEdit(change, blobIds.getValue(change.path)!!, File(workTree, change.path)))
        }
      }
      return if (editor.commit()) IndexUpdateResult.Ok else IndexUpdateResult.Conflicted
    } finally {
      // No-op once the editor committed; the release that matters is on the Conflicted path.
      index.unlock()
    }
  }

  private fun entryEdit(change: PatchChange, blobId: ObjectId, file: File) =
    object : DirCacheEditor.PathEdit(change.path) {
      override fun apply(entry: DirCacheEntry) {
        entry.fileMode = change.mode
        entry.setObjectId(blobId)
        entry.setLength(change.content!!.size.toLong())
        // NOFOLLOW: for a symlink the stat that matters is the link's, not its target's.
        entry.setLastModified(Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS).toInstant())
      }
    }

  companion object {
    /**
     * How many of [paths] have a stage-0 index entry that differs from the HEAD tree entry —
     * different blob, different mode, present on only one side, or left in a conflicted stage.
     * Zero means the index still matches HEAD for every path.
     */
    fun countIndexEntriesDifferingFromHead(
      repo: Repository,
      paths: List<String>,
      headTreeId: ObjectId,
    ): Int {
      val index = DirCache.read(repo)
      return paths.count { path -> !indexMatchesHead(repo, index, path, headTreeId) }
    }

    private fun indexMatchesHead(
      repo: Repository,
      index: DirCache,
      path: String,
      headTreeId: ObjectId,
    ): Boolean {
      val entry: DirCacheEntry? = index.getEntry(path)
      // A conflicted path is never "the same as HEAD": leave it to the user to resolve.
      if (entry != null && entry.stage != DirCacheEntry.STAGE_0) return false
      val head = TreeWalk.forPath(repo, path, headTreeId) ?: return entry == null
      return head.use { walk ->
        entry != null && entry.objectId == walk.getObjectId(0) && entry.fileMode == walk.getFileMode(0)
      }
    }
  }
}
