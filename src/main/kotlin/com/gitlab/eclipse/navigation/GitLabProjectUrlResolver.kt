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

  fun resolveBlobUrl(file: File, startLine: Int?, endLine: Int?): Resolution =
    withRepo(file) { repo ->
      val relPath = repo.workTree.toPath().relativize(file.toPath()).toString().replace('\\', '/')
      if (relPath.startsWith("..")) return@withRepo Resolution.Warn(NOT_IN_REPO)
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

  private fun webUrlFor(repo: Repository): Resolution {
    val instanceUrl = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL).trimEnd('/')
    if (instanceUrl.isBlank()) return Resolution.Warn(NO_INSTANCE)
    val config = repo.config
    val origin = config.getString("remote", "origin", "url")
    val remoteUrl = origin ?: config.getSubsections("remote").asSequence()
      .mapNotNull { name -> config.getString("remote", name, "url") }
      .firstOrNull { url ->
        GitLabRemoteParser.parseGitLabRemote(url, instanceUrl)
          ?.let { GitLabRemoteParser.remoteMatchesInstance(it, instanceUrl) } == true
      }
      ?: return Resolution.Warn(NO_REMOTE)
    val remote = GitLabRemoteParser.parseGitLabRemote(remoteUrl, instanceUrl)
      ?: return Resolution.Warn(NO_REMOTE)
    if (!GitLabRemoteParser.remoteMatchesInstance(remote, instanceUrl)) return Resolution.Warn(MISMATCH)
    return Resolution.Ok("$instanceUrl/${PathSegmentEncoder.encodePath(remote.namespaceWithPath)}")
  }

  private fun anchor(startLine: Int?, endLine: Int?): String {
    if (startLine == null) return ""
    val suffix = if (endLine != null && endLine > startLine) "-${endLine + 1}" else ""
    return "#L${startLine + 1}$suffix"
  }

  private inline fun withRepo(start: File, block: (Repository) -> Resolution): Resolution? {
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
