package com.gitlab.eclipse.snippets

import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Compares the index against HEAD for a set of paths (design §9.4-5-4 / §9.4-5-8).
 *
 * Used twice, and the second use is the point: once before anything is written, so a staged change
 * aborts the apply instead of being silently discarded (A19), and once immediately after the index
 * lock is taken, because an external `git add` can land between the working-tree writes and the
 * index update.
 */
class PatchIndexUpdater {
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
