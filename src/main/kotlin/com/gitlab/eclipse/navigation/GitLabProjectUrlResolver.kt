package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.File

/**
 * Full identity of a resolved GitLab project (Phase 3 §6.1). [gitDir]/[workTree] come straight
 * from JGit and are NOT canonicalized here — callers canonicalize as needed.
 */
data class GitLabProjectInfo(
  val gitDir: File,
  val workTree: File,
  val namespaceWithPath: String,
  val instanceUrl: String,
  val webUrl: String,
  val remoteName: String,
)

/**
 * A-plan (no API) URL resolution: git remote → namespaceWithPath → ${gitlab.url}/${namespaceWithPath}.
 * All JGit access is local I/O; call from a background thread. Returns Ok(url) or Warn(message) — never throws.
 */
class GitLabProjectUrlResolver(
  private val preferenceStore: ScopedPreferenceStore = service(),
) {
  private val logger by lazy { logger<GitLabProjectUrlResolver>() }

  sealed interface Resolution {
    data class Ok(val url: String) : Resolution
    data class Warn(val message: String) : Resolution
  }

  /**
   * Context-returning counterpart of [Resolution] (Phase 3 §6.1). A separate type on purpose:
   * [Resolution.Ok] is compared by equality in existing callers/tests, so extending it with new
   * fields would be a behavioral break. Warn messages are shared with the URL-returning API.
   */
  sealed interface ContextResolution {
    data class Ok(val project: GitLabProjectInfo) : ContextResolution
    data class Warn(val message: String) : ContextResolution
  }

  private companion object {
    const val NOT_IN_REPO = "The current file is not in the project repository."
    const val NOT_COMMITTED = "No link exists for the current file. Commit the current file to the repository."
    const val NO_INSTANCE = "Set your GitLab instance URL in the GitLab preferences."
    const val MISMATCH = "The current project does not match your configured GitLab instance."
    const val NO_REMOTE = "No GitLab remote is configured for the current project."
  }

  fun resolveWebUrlForRepo(repoDir: File): Resolution =
    withRepo(repoDir) { repo -> webUrlFor(repo) } ?: Resolution.Warn(NOT_IN_REPO)

  fun resolveWebUrlForFile(file: File): Resolution =
    withRepo(file) { repo -> webUrlFor(repo) } ?: Resolution.Warn(NOT_IN_REPO)

  /** Like [resolveWebUrlForRepo] but returns the full project identity. Never throws. */
  fun resolveContextForRepo(repoDir: File): ContextResolution =
    withRepo(repoDir) { repo -> contextFor(repo) } ?: ContextResolution.Warn(NOT_IN_REPO)

  /** Like [resolveWebUrlForFile] but returns the full project identity. Never throws. */
  fun resolveContextForFile(file: File): ContextResolution =
    withRepo(file) { repo -> contextFor(repo) } ?: ContextResolution.Warn(NOT_IN_REPO)

  fun resolveBlobUrl(file: File, startLine: Int?, endLine: Int?): Resolution =
    withRepo(file) { repo ->
      val relPath = repo.workTree.toPath().relativize(file.toPath()).toString().replace('\\', '/')
      if (relPath == ".." || relPath.startsWith("../")) return@withRepo Resolution.Warn(NOT_IN_REPO)
      // A repo with zero commits has no HEAD; log() throws NoHeadException rather than
      // returning an empty result — that also means "this file was never committed".
      val sha = Git(repo).use {
        try {
          it.log().addPath(relPath).setMaxCount(1).call().firstOrNull()?.name
        } catch (@Suppress("SwallowedException") e: NoHeadException) {
          // A brand-new repo (no commits at all) has no HEAD to log from; that is
          // itself "this file was never committed", not an error to surface.
          null
        }
      } ?: return@withRepo Resolution.Warn(NOT_COMMITTED)
      when (val base = webUrlFor(repo)) {
        is Resolution.Warn -> base
        is Resolution.Ok -> Resolution.Ok(
          "${base.url}/-/blob/${PathSegmentEncoder.encodeSegment(sha)}/" +
            PathSegmentEncoder.encodePath(relPath) + anchor(startLine, endLine),
        )
      }
    } ?: Resolution.Warn(NOT_IN_REPO)

  private fun webUrlFor(repo: Repository): Resolution = when (val match = matchRemote(repo)) {
    is RemoteMatch.Hit -> Resolution.Ok(match.webUrl)
    is RemoteMatch.Miss -> Resolution.Warn(match.message)
  }

  private fun contextFor(repo: Repository): ContextResolution {
    val match = when (val m = matchRemote(repo)) {
      is RemoteMatch.Hit -> m
      is RemoteMatch.Miss -> return ContextResolution.Warn(m.message)
    }
    // A bare repo has no work tree to anchor branch/MR operations on; repo.workTree would
    // throw NoWorkTreeException (caught by withRepo → NOT_IN_REPO) — bail out explicitly.
    if (repo.isBare) return ContextResolution.Warn(NOT_IN_REPO)
    return ContextResolution.Ok(
      GitLabProjectInfo(
        gitDir = repo.directory,
        workTree = repo.workTree,
        namespaceWithPath = match.remote.namespaceWithPath,
        instanceUrl = match.instanceUrl,
        webUrl = match.webUrl,
        remoteName = match.remoteName,
      ),
    )
  }

  private sealed interface RemoteMatch {
    data class Hit(val remoteName: String, val remote: GitLabRemote, val instanceUrl: String) : RemoteMatch {
      // namespaceWithPath is derived from the remote URL's rawPath, so it is already in
      // URL-path form; re-encoding it would double-encode escapes from HTTP(S) remotes
      // (e.g. gr%C3%BCp → gr%25C3%25BCp). Use it verbatim.
      val webUrl: String get() = "$instanceUrl/${remote.namespaceWithPath}"
    }

    data class Miss(val message: String) : RemoteMatch
  }

  private fun matchRemote(repo: Repository): RemoteMatch {
    val instanceUrl = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL).trimEnd('/')
    if (instanceUrl.isBlank()) return RemoteMatch.Miss(NO_INSTANCE)
    // Prefer origin, but fall back to any other remote that matches the instance —
    // VSCode's parseProjects collects the remotes matching the instance instead of
    // privileging a non-matching (or unparsable) origin.
    val config = repo.config
    val names = config.getSubsections("remote")
    val ordered = (if ("origin" in names) listOf("origin") else emptyList()) + names.filter { it != "origin" }
    val parsed = ordered.mapNotNull { name ->
      config.getString("remote", name, "url")
        ?.let { url -> GitLabRemoteParser.parseGitLabRemote(url, instanceUrl) }
        ?.let { remote -> name to remote }
    }
    val hit = parsed.firstOrNull { (_, remote) -> GitLabRemoteParser.remoteMatchesInstance(remote, instanceUrl) }
      // A GitLab-shaped remote exists but none targets this instance → MISMATCH;
      // nothing even parsed as a GitLab remote → NO_REMOTE.
      ?: return RemoteMatch.Miss(if (parsed.isEmpty()) NO_REMOTE else MISMATCH)
    return RemoteMatch.Hit(hit.first, hit.second, instanceUrl)
  }

  private fun anchor(startLine: Int?, endLine: Int?): String {
    if (startLine == null) return ""
    val suffix = if (endLine != null && endLine > startLine) "-${endLine + 1}" else ""
    return "#L${startLine + 1}$suffix"
  }

  private inline fun <T : Any> withRepo(start: File, block: (Repository) -> T): T? {
    // The block runs inside the try on purpose: public resolve* methods must NEVER throw
    // (callers share a coroutine scope), so any JGit failure — bare repo (NoWorkTreeException),
    // relativize on mismatched paths, corrupt pack during log(), config reload race — is
    // logged and mapped to null (→ NOT_IN_REPO Warn) instead of escaping.
    return try {
      val repo = FileRepositoryBuilder().findGitDir(start).setMustExist(true).build()
      repo.use { block(it) }
    } catch (e: Exception) {
      logger.warn("Could not resolve a GitLab URL for ${start.path}.", e)
      null
    }
  }
}
