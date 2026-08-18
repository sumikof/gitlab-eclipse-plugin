package com.gitlab.eclipse.clone

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.io.IOException

/**
 * Reads the clone destination to decide whether a clone may start, and whether anything is left
 * behind after one failed.
 *
 * Deliberately does NOT try to decide whether an existing clone COMPLETED. JGit writes the
 * `origin` remote before the real fetch and updates `HEAD` before the checkout, and a normally
 * cloned empty repository has no resolvable `HEAD` at all, so no on-disk signal separates a
 * finished clone from an interrupted one. [Verdict.SameRepository] therefore means "same
 * repository, unknown completeness" — the caller must ask the user for consent, never treat it
 * as done.
 */
class CloneDestinationInspector {

  sealed interface Verdict {
    /** Absent or empty: a clone may start here. */
    data object Empty : Verdict

    /** A git repository whose `origin` is the url about to be cloned. Completeness is unknown. */
    data object SameRepository : Verdict

    /** Non-empty and not the same repository: do not clone, do not offer to delete anything. */
    data object Occupied : Verdict
  }

  fun inspect(destination: File, cloneUrl: String): Verdict {
    if (!hasLeftovers(destination)) return Verdict.Empty
    val origin = originUrlOf(destination) ?: return Verdict.Occupied
    return if (origin == cloneUrl) Verdict.SameRepository else Verdict.Occupied
  }

  /**
   * True when the destination holds anything at all.
   *
   * Emptiness, not existence: JGit's failure cleanup deletes the children of a directory the user
   * had created beforehand and leaves the directory itself, so `exists()` would report leftovers
   * that are not there and send the user looking for a partial clone that was already removed.
   */
  fun hasLeftovers(destination: File): Boolean {
    // A non-directory is checked before emptiness: `list()` returns null for one, so a plain file
    // sitting at the destination would otherwise read as "nothing there" and be reported to the
    // user as an empty location while their file is still on disk.
    if (destination.exists() && !destination.isDirectory) return true
    val entries = destination.list() ?: return false
    return entries.isNotEmpty()
  }

  /** `origin`'s url, or null when this is not a readable git repository. */
  private fun originUrlOf(destination: File): String? =
    try {
      openRepository(destination)?.use { repo ->
        repo.config.getString("remote", "origin", "url")
      }
    } catch (_: IOException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }

  private fun openRepository(destination: File): Repository? {
    val gitDir = File(destination, ".git")
    if (!gitDir.exists()) return null
    val repository = FileRepositoryBuilder().setGitDir(gitDir).build()
    if (repository.objectDatabase.exists()) return repository
    repository.close() // a `.git` with no object database: nothing to read, and nothing to leak
    return null
  }
}
