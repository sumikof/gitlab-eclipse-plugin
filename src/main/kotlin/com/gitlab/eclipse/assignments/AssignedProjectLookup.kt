package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.ui.preferences.ScopedPreferenceStore

/**
 * The window through which project resolution consults the user's assignments (design §9.7 / §11.1).
 *
 * [Result.None] is the answer whenever there is nothing to apply, and it is reached without any
 * work at all when no assignment exists anywhere — that emptiness check is what makes A8 true:
 * with an empty store the ordinary resolution runs exactly as before, down to not reading the
 * repository's remotes.
 *
 * A stored assignment that fails validation is NOT silently ignored (§11.1): the user chose it, so
 * they are told why it was not used, rather than quietly getting a different project.
 */
class AssignedProjectLookup(
  private val store: SelectedProjectStore = SelectedProjectStore(),
  private val validator: AssignmentValidator = AssignmentValidator(),
  private val preferenceStore: ScopedPreferenceStore = service(),
) {
  sealed interface Result {
    data class Use(val project: GitLabProjectInfo) : Result

    /** An assignment exists but must not be used; the caller surfaces [message] (A14). */
    data class Warn(val message: String) : Result

    /** No applicable assignment: the caller resolves the project the usual way (A8). */
    data object None : Result
  }

  fun forRepository(repo: Repository): Result {
    // A8's fast path: nothing assigned anywhere, so do not even look at the repository.
    if (store.isEmpty()) return Result.None
    // A bare repository has no work tree to key an assignment on, and no files to act on either.
    if (repo.isBare) return Result.None
    val assignment = store.find(repositoryKeyOf(repo)) ?: return Result.None

    val currentInstanceUrl = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL).orEmpty()
    val remotes = remotesOf(repo)
    return when (val check = validator.check(assignment, currentInstanceUrl, remotes.values.toList())) {
      AssignmentCheck.Accepted -> use(assignment, repo, remotes)
      is AssignmentCheck.Rejected -> Result.Warn(messageFor(check.reason))
    }
  }

  /** The key an assignment is stored under: the repository's work tree, canonicalised. */
  fun repositoryKeyOf(repo: Repository): String = canonicalPathOf(repo)

  private fun use(
    assignment: ProjectAssignment,
    repo: Repository,
    remotes: Map<String, String>,
  ): Result {
    // check() already established that one matches; this only recovers which.
    val remoteName = validator.matchingRemoteName(assignment, remotes) ?: return Result.None
    return Result.Use(
      GitLabProjectInfo(
        gitDir = repo.directory,
        workTree = repo.workTree,
        namespaceWithPath = assignment.namespaceWithPath,
        instanceUrl = assignment.instanceUrl,
        webUrl = "${assignment.instanceUrl.trimEnd('/')}/${assignment.namespaceWithPath}",
        remoteName = remoteName,
      ),
    )
  }

  private fun remotesOf(repo: Repository): Map<String, String> =
    repo.config.getSubsections(REMOTE_SECTION)
      .mapNotNull { name -> repo.config.getString(REMOTE_SECTION, name, URL_KEY)?.let { name to it } }
      .toMap()

  private fun canonicalPathOf(repo: Repository): String =
    try {
      repo.workTree.canonicalPath
    } catch (_: Exception) {
      repo.workTree.absolutePath
    }

  private fun messageFor(reason: AssignmentCheck.Reason): String = when (reason) {
    AssignmentCheck.Reason.INSTANCE_CHANGED ->
      "The GitLab project assigned to this repository belongs to a different instance, so it was not used."
    AssignmentCheck.Reason.REMOTE_GONE ->
      "The remote this repository's GitLab project was assigned for no longer exists, so it was not used."
    AssignmentCheck.Reason.REMOTE_NOT_ON_INSTANCE ->
      "The remote this repository's GitLab project was assigned for is not on that instance, so it was not used."
  }

  private companion object {
    const val REMOTE_SECTION = "remote"
    const val URL_KEY = "url"
  }
}
