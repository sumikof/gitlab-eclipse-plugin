package com.gitlab.eclipse.clone

/**
 * Derives a GitLab wiki clone URL from a project's `http_url_to_repo`.
 *
 * The reference implementation (`gitlab_remote_source.ts:35-37`) does
 * `url.replace(/\.git$/, '.wiki.git')`, which returns the input UNCHANGED when the URL does not
 * end in `.git` — GitLab accepts `https://host/group/project` remotes and `GitLabRemoteParser`
 * treats the suffix as optional, so that input reaches here. Cloning the input unchanged would
 * fetch the project itself instead of its wiki and look like a success, so the suffix is treated
 * as optional here on purpose.
 */
object WikiUrlDeriver {
  private const val GIT_SUFFIX = ".git"
  private const val WIKI_SUFFIX = ".wiki.git"

  fun derive(httpUrlToRepo: String): String =
    httpUrlToRepo.trimEnd('/').removeSuffix(GIT_SUFFIX) + WIKI_SUFFIX
}
