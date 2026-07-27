package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.utils.logger
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * Read-only snapshot of the current branch state of a git repository (Phase 3 §8.3).
 *
 * [name] is the HEAD branch's short name, or null when HEAD is detached. [trackingBranch] and
 * [upstreamRemote] are the raw `branch.<name>.merge` (with `refs/heads/` stripped) and
 * `branch.<name>.remote` git config values — this is deliberately NOT compared against any
 * resolved remote here; matching [upstreamRemote] against a [RepositoryContext.remoteName] is the
 * caller's job (§8.3). [headSha] is the full HEAD commit ObjectId name, or null with no commits.
 */
data class CurrentBranch(
  val name: String?,
  val trackingBranch: String?,
  val hasUpstream: Boolean,
  val upstreamRemote: String?,
  val headSha: String?,
)

/**
 * Reads [CurrentBranch] state for a git directory via JGit read-only APIs: no fetch, no push, no
 * checkout. All JGit access is local I/O; call from a background thread.
 */
class CurrentBranchGitReader {
  private val logger by lazy { logger<CurrentBranchGitReader>() }

  private companion object {
    const val REFS_HEADS_PREFIX = "refs/heads/"
  }

  /**
   * Opens [gitDir] read-only and reads its current branch state. Never throws: any failure
   * (missing/bad directory, bare repo, no HEAD) is logged and mapped to an all-null
   * [CurrentBranch] instead of escaping to the caller.
   */
  fun read(gitDir: File): CurrentBranch =
    try {
      FileRepositoryBuilder().findGitDir(gitDir).setMustExist(true).build().use { repo ->
        val name = repo.branch?.takeIf { repo.fullBranch == "$REFS_HEADS_PREFIX$it" }
        val trackingBranch = name
          ?.let { repo.config.getString("branch", it, "merge") }
          ?.removePrefix(REFS_HEADS_PREFIX)
        val upstreamRemote = name?.let { repo.config.getString("branch", it, "remote") }
        val headSha = repo.resolve("HEAD")?.name
        CurrentBranch(
          name = name,
          trackingBranch = trackingBranch,
          hasUpstream = trackingBranch != null && upstreamRemote != null,
          upstreamRemote = upstreamRemote,
          headSha = headSha,
        )
      }
    } catch (e: Exception) {
      logger.warn("Could not read current branch state for ${gitDir.path}.", e)
      CurrentBranch(null, null, false, null, null)
    }
}
