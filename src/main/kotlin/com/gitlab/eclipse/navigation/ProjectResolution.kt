package com.gitlab.eclipse.navigation

/**
 * Typed outcome of [GitLabProjectUrlResolver.resolveProjectForFile] (Quick Chat design §9.2.2).
 *
 * Unlike [GitLabProjectUrlResolver.ContextResolution], "not in a repository" and "resolution
 * failed" are different values here: Quick Chat sends a question about a file outside any project
 * without a project scope, but must not send one whose project it could not determine (fail closed).
 */
sealed interface ProjectResolution {
  /** The file belongs to [project]; its instance may still differ from the connected one. */
  data class Resolved(val project: GitLabProjectInfo) : ProjectResolution

  /** No location, no Git work tree around the file, or a bare repository. */
  data object NotInRepository : ProjectResolution

  /** A Git work tree, but no remote (or assignment) names a project on the configured instance. */
  data object NoGitLabRemote : ProjectResolution

  /** Resolution could not decide: no configured instance, an unusable assignment, or an exception. */
  data object Failed : ProjectResolution
}
