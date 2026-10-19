package com.gitlab.eclipse.mergerequests.diff

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** What the local object database says about one diff side. */
sealed interface LocalBlob {
  /** The blob's complete content. */
  class Found(val bytes: ByteArray) : LocalBlob

  /** The commit is local but holds no regular file at the path (absent, or a directory). */
  object NotInTree : LocalBlob

  /** The path is a submodule entry. */
  object Gitlink : LocalBlob

  /** The blob is larger than the cap; it was not read. */
  object TooLarge : LocalBlob

  /** No candidate repository holds the commit (or could be read). */
  object Unavailable : LocalBlob
}

/**
 * Reads a diff side from the object database of a local clone (REVIEW-01 design §5.1, N5).
 *
 * Read-only by construction: it parses the commit named by the side's SHA, walks that commit's tree
 * to the path and opens the blob — it never fetches, checks out, or touches the index, the working
 * tree or the refs, so uncommitted edits and the checked-out branch cannot leak into a diff. It takes
 * no `GitOperationGuard` for the same reason. Each of [gitDirs] is opened for one read and closed
 * again; the first whose object database holds the commit answers.
 */
class LocalBlobReader(
  private val openRepository: (File) -> Repository = ::openExistingGitDir,
  private val gitDirs: () -> List<File>,
) {
  /** The blob for [side], capped at [maxBytes]. */
  fun read(side: SideRef, maxBytes: Int): LocalBlob {
    // JGit's ObjectId is SHA-1 only: a 64-hex (SHA-256) SHA cannot be read locally and goes to REST.
    if (!ObjectId.isId(side.sha)) return LocalBlob.Unavailable
    val commitId = ObjectId.fromString(side.sha)
    for (gitDir in gitDirs()) {
      val answer = readFrom(gitDir, commitId, side.path, maxBytes)
      if (answer != null) return answer
    }
    return LocalBlob.Unavailable
  }

  /**
   * The answer from [gitDir], or `null` when it lacks the commit or cannot be read — an I/O error or
   * any JGit runtime failure (a corrupt pack, a malformed object) — so the next candidate, and in
   * the end REST, is tried. Cancellation is not a read failure and propagates.
   */
  private fun readFrom(gitDir: File, commitId: ObjectId, path: String, maxBytes: Int): LocalBlob? = try {
    openRepository(gitDir).use { repository ->
      if (repository.objectDatabase.has(commitId)) blobAt(repository, commitId, path, maxBytes) else null
    }
  } catch (e: CancellationException) {
    throw e
  } catch (_: IOException) {
    null
  } catch (_: RuntimeException) {
    null
  }

  private fun blobAt(repository: Repository, commitId: ObjectId, path: String, maxBytes: Int): LocalBlob {
    val tree = RevWalk(repository).use { it.parseCommit(commitId).tree }
    val walk = TreeWalk.forPath(repository, path, tree) ?: return LocalBlob.NotInTree
    return walk.use {
      val mode = it.getFileMode(0)
      when {
        mode == FileMode.GITLINK -> LocalBlob.Gitlink
        mode.objectType != Constants.OBJ_BLOB -> LocalBlob.NotInTree
        else -> {
          val loader = repository.open(it.getObjectId(0), Constants.OBJ_BLOB)
          if (loader.size > maxBytes) LocalBlob.TooLarge else LocalBlob.Found(loader.bytes)
        }
      }
    }
  }
}

/** Opens the existing repository whose git directory is exactly [gitDir]; never creates one. */
private fun openExistingGitDir(gitDir: File): Repository =
  FileRepositoryBuilder().setGitDir(gitDir).setMustExist(true).build()
