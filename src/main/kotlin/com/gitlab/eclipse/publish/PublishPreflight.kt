package com.gitlab.eclipse.publish

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/** What [PublishPreflight] decided. Only [Fresh] leads to creating a project. */
sealed interface Preflight {
  /** Nothing recorded and the folder is publishable. The counts feed the confirmation dialog. */
  data class Fresh(
    val isRepository: Boolean,
    val trackableFileCount: Int,
    val hasGitignore: Boolean,
  ) : Preflight

  /** A24: the project already exists. Add the recorded remote if [remoteMissing], then push only. */
  data class ResumePush(val state: PublishRecord.State, val remoteMissing: Boolean) : Preflight

  /** An unconfirmed intent: the caller runs the recovery protocol (design §9.6). */
  data class RecoverIntent(val intent: PublishRecord.Intent) : Preflight

  enum class Stop { REMOTE_EXISTS, DETACHED_HEAD, NOT_A_DIRECTORY, NO_FILES, INSTANCE_MISMATCH }

  data class Blocked(val reason: Stop) : Preflight
}

/**
 * Phase 0 of publishing: decide what to do, changing nothing (design §9.6).
 *
 * The order matters and is the point of the class. **The record is consulted before the remote**
 * (§9.6 R8-1). A [PublishRecord.State] is persisted BEFORE the remote is added, so a process that
 * dies in between leaves "state recorded, no remote". Judging on the remote alone would send that
 * case down the fresh flow, overwrite the state with a new intent, `POST /projects` a second time,
 * and lose the only record of the project already created.
 *
 * Nothing here runs `init` either (§9.6-3 / R2-1): cancelling at the confirmation dialog must not
 * leave a `.git` behind, so the repository is only created once the user has confirmed.
 *
 * Blocking local I/O — call from a background thread.
 */
class PublishPreflight(private val records: PublishRecordStore = PublishRecordStore()) {
  fun inspect(folder: File, instanceUrl: String): Preflight {
    if (!folder.isDirectory) return Preflight.Blocked(Preflight.Stop.NOT_A_DIRECTORY)
    val key = folder.canonicalPath

    return when (val record = records.find(key)) {
      is PublishRecord.State -> fromState(folder, record, instanceUrl)
      is PublishRecord.Intent ->
        if (sameInstance(record.instanceUrl, instanceUrl)) {
          Preflight.RecoverIntent(record)
        } else {
          // §9.6 recovery step 1: do not attempt recovery against a different instance, and keep
          // the record — the original instance may come back.
          Preflight.Blocked(Preflight.Stop.INSTANCE_MISMATCH)
        }
      null -> fresh(folder)
    }
  }

  private fun fromState(
    folder: File,
    state: PublishRecord.State,
    instanceUrl: String,
  ): Preflight {
    if (!sameInstance(state.instanceUrl, instanceUrl)) {
      return Preflight.Blocked(Preflight.Stop.INSTANCE_MISMATCH)
    }
    val remotes = remoteUrls(folder)
    return when {
      remotes.any { RemoteUrlNormalizer.normalize(it) == state.normalizedRemoteUrl } ->
        Preflight.ResumePush(state, remoteMissing = false)
      // A24: the project exists but its remote is gone. Re-add and push; never POST again.
      remotes.isEmpty() -> Preflight.ResumePush(state, remoteMissing = true)
      // Some other remote is configured: this is not the situation we recorded, so stop rather
      // than guess which one to push to.
      else -> Preflight.Blocked(Preflight.Stop.REMOTE_EXISTS)
    }
  }

  private fun fresh(folder: File): Preflight {
    val isRepository = File(folder, Constants.DOT_GIT).exists()
    if (isRepository) {
      openRepository(folder)?.use { repo ->
        if (repo.config.getSubsections(REMOTE_SECTION).isNotEmpty()) {
          return Preflight.Blocked(Preflight.Stop.REMOTE_EXISTS)
        }
        if (isDetached(repo)) return Preflight.Blocked(Preflight.Stop.DETACHED_HEAD)
      }
    }
    val fileCount = trackableFileCount(folder)
    if (fileCount == 0) return Preflight.Blocked(Preflight.Stop.NO_FILES)
    return Preflight.Fresh(
      isRepository = isRepository,
      trackableFileCount = fileCount,
      hasGitignore = File(folder, GITIGNORE).isFile,
    )
  }

  private fun remoteUrls(folder: File): List<String> =
    openRepository(folder)?.use { repo ->
      repo.config.getSubsections(REMOTE_SECTION)
        .mapNotNull { repo.config.getString(REMOTE_SECTION, it, URL_KEY) }
    }.orEmpty()

  private fun openRepository(folder: File): Repository? =
    try {
      FileRepositoryBuilder().setGitDir(File(folder, Constants.DOT_GIT)).setMustExist(true).build()
    } catch (_: Exception) {
      null
    }

  /** `Repository.getBranch()` answers with the object id itself when HEAD is detached. */
  private fun isDetached(repo: Repository): Boolean {
    val head = repo.resolve(Constants.HEAD) ?: return false
    return repo.branch == head.name
  }

  /**
   * Files that could end up in the initial commit, `.git` aside.
   *
   * `.gitignore` is deliberately NOT interpreted: this number backs the confirmation dialog, where
   * it is an upper bound, and the warning next to it is precisely "with no ignore rules everything
   * here becomes public". What actually gets committed is decided by `AddCommand`, which does
   * honour `.gitignore`.
   */
  private fun trackableFileCount(folder: File): Int =
    folder.walkTopDown()
      .onEnter { it.name != Constants.DOT_GIT }
      .count { it.isFile }

  private fun sameInstance(recorded: String, current: String): Boolean =
    recorded.trimEnd('/').equals(current.trimEnd('/'), ignoreCase = true)

  private companion object {
    const val REMOTE_SECTION = "remote"
    const val URL_KEY = "url"
    const val GITIGNORE = ".gitignore"
  }
}
