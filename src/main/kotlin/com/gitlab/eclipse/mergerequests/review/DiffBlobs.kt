package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException

/**
 * The local blob facts [classifyDiff] needs for an empty `diff` (design §12.2.1). Background only:
 * both calls read the local object database. Neither ever throws; an unanswerable question
 * resolves to the safe side ([headBlobIsEmpty] `false`, [blobIdsEqual] `null`), which classifies
 * the file as [DiffLineMap.Unavailable].
 */
interface DiffBlobs {
  /** Whether [newPath] at commit [headSha] is an empty blob. `false` when that cannot be read. */
  fun headBlobIsEmpty(headSha: String, newPath: String): Boolean

  /**
   * Whether [oldPath] at [baseSha] and [newPath] at [headSha] are the same blob, or `null` when
   * either commit is not available locally. A path missing from its commit is `false`.
   */
  fun blobIdsEqual(baseSha: String, oldPath: String, headSha: String, newPath: String): Boolean?
}

/** [DiffBlobs] over one open [repository]; the caller owns (and closes) it. */
class RepositoryDiffBlobs(private val repository: Repository) : DiffBlobs {
  override fun headBlobIsEmpty(headSha: String, newPath: String): Boolean = readSafely(false) {
    val tree = treeOf(headSha) ?: return@readSafely false
    val blob = blobIn(tree, newPath) ?: return@readSafely false
    repository.open(blob, Constants.OBJ_BLOB).size == 0L
  }

  override fun blobIdsEqual(baseSha: String, oldPath: String, headSha: String, newPath: String): Boolean? =
    readSafely(null) {
      val baseTree = treeOf(baseSha) ?: return@readSafely null
      val headTree = treeOf(headSha) ?: return@readSafely null
      val baseBlob = blobIn(baseTree, oldPath) ?: return@readSafely false
      val headBlob = blobIn(headTree, newPath) ?: return@readSafely false
      baseBlob == headBlob
    }

  /** Whether the object database holds commit [sha]. */
  internal fun hasCommit(sha: String): Boolean = readSafely(false) { treeOf(sha) != null }

  /** The tree of commit [sha], or `null` when [sha] is malformed or not in the local object database. */
  private fun treeOf(sha: String): RevTree? {
    if (!ObjectId.isId(sha)) return null
    val id = ObjectId.fromString(sha)
    if (!repository.objectDatabase.has(id)) return null
    return RevWalk(repository).use { it.parseCommit(id).tree }
  }

  /** The id of the regular blob at [path] in [tree], or `null` when there is none. */
  private fun blobIn(tree: RevTree, path: String): ObjectId? =
    TreeWalk.forPath(repository, path, tree)?.use { walk ->
      walk.getObjectId(0).takeIf { walk.getFileMode(0).objectType == Constants.OBJ_BLOB }
    }

  private inline fun <T> readSafely(fallback: T, read: () -> T): T = try {
    read()
  } catch (_: IOException) {
    fallback
  } catch (_: IllegalArgumentException) {
    fallback
  }
}

/**
 * [DiffBlobs] for a loader that knows only the MR, not the repository: answers from the first of
 * [gitDirs] whose object database holds the head commit, and gives the safe answer when none does.
 * Each repository is opened for one question and closed again.
 */
class WorkspaceDiffBlobs(
  private val gitDirs: () -> List<File> = { RepositoryContextResolver().candidateContexts().map { File(it.gitDir) } },
  private val openRepository: (File) -> Repository = ::openGitDir,
) : DiffBlobs {
  override fun headBlobIsEmpty(headSha: String, newPath: String): Boolean =
    withRepositoryHaving(headSha, false) { it.headBlobIsEmpty(headSha, newPath) }

  override fun blobIdsEqual(baseSha: String, oldPath: String, headSha: String, newPath: String): Boolean? =
    withRepositoryHaving(headSha, null) { it.blobIdsEqual(baseSha, oldPath, headSha, newPath) }

  private fun <T> withRepositoryHaving(headSha: String, fallback: T, read: (RepositoryDiffBlobs) -> T): T {
    for (gitDir in gitDirs()) {
      openOrNull(gitDir)?.use {
        val blobs = RepositoryDiffBlobs(it)
        if (blobs.hasCommit(headSha)) return read(blobs)
      }
    }
    return fallback
  }

  private fun openOrNull(gitDir: File): Repository? = try {
    openRepository(gitDir)
  } catch (_: IOException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}

/** Opens the existing repository at (or above) [gitDir], as [com.gitlab.eclipse.mergerequests.CurrentBranchGitReader] does. */
internal fun openGitDir(gitDir: File): Repository =
  FileRepositoryBuilder().findGitDir(gitDir).setMustExist(true).build()
