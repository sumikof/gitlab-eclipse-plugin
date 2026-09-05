package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.mergerequests.GitAuthConfigurer

/** Whether an assignment may be used, and if not, why (design §11.1). */
sealed interface AssignmentCheck {
  data object Accepted : AssignmentCheck

  enum class Reason {
    /** The assignment was made against a different GitLab instance than the configured one. */
    INSTANCE_CHANGED,

    /** No remote in the repository matches the one the assignment was made for. */
    REMOTE_GONE,

    /** The assigned remote does not belong to the instance the assignment names. */
    REMOTE_NOT_ON_INSTANCE,
  }

  data class Rejected(val reason: Reason) : AssignmentCheck
}

/**
 * Decides whether a stored assignment may override the normal project resolution (design §11.1).
 *
 * The assignment path runs BEFORE the ordinary resolution, so it bypasses the check that the
 * repository's remote belongs to the configured instance. All three conditions below exist to put
 * that guarantee back; each one alone leaves a way to send writes to the wrong project.
 *
 * Condition 3 is the least obvious and was added in review (R2-5): conditions 1 and 2 together only
 * establish "the assignment names the instance we are connected to" and "that remote still exists".
 * A remote pointing at another GitLab instance — or at GitHub — satisfies both, and the assignment
 * would then resolve this repository to a project on the connected instance that has nothing to do
 * with it.
 */
class AssignmentValidator(private val auth: GitAuthConfigurer = GitAuthConfigurer()) {
  fun check(
    assignment: ProjectAssignment,
    currentInstanceUrl: String,
    repositoryRemoteUrls: List<String>,
  ): AssignmentCheck {
    if (!auth.hostMatchesInstance(assignment.instanceUrl, currentInstanceUrl)) {
      return AssignmentCheck.Rejected(AssignmentCheck.Reason.INSTANCE_CHANGED)
    }
    if (repositoryRemoteUrls.none { sameRemote(it, assignment.remoteUrl) }) {
      return AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_GONE)
    }
    if (!remoteBelongsTo(assignment.remoteUrl, assignment.instanceUrl)) {
      return AssignmentCheck.Rejected(AssignmentCheck.Reason.REMOTE_NOT_ON_INSTANCE)
    }
    return AssignmentCheck.Accepted
  }

  /**
   * Which of [remotes] (name to url) the assignment was made for, or null when none is. Split from
   * [check] so the same comparison decides acceptance and names the remote, without either
   * restating it.
   */
  fun matchingRemoteName(assignment: ProjectAssignment, remotes: Map<String, String>): String? =
    remotes.entries.firstOrNull { sameRemote(it.value, assignment.remoteUrl) }?.key

  /**
   * Two remote URLs address the same repository: same authority, same path once a trailing `.git`
   * and slashes are gone. The path's case is kept — GitLab paths are case sensitive.
   */
  private fun sameRemote(left: String, right: String): Boolean {
    val leftAuthority = RemoteAuthorityParser.parse(left) ?: return false
    val rightAuthority = RemoteAuthorityParser.parse(right) ?: return false
    if (!authoritiesMatch(leftAuthority, rightAuthority)) return false
    return pathOf(left) == pathOf(right)
  }

  /** Condition 3: does this remote live on that instance? */
  private fun remoteBelongsTo(remoteUrl: String, instanceUrl: String): Boolean {
    // An http(s) remote is exactly what GitAuthConfigurer already compares, including the
    // effective port — reuse it rather than restate the rule.
    if (remoteUrl.trim().startsWith(HTTP_SCHEME_PREFIX, ignoreCase = true)) {
      return auth.hostMatchesInstance(remoteUrl, instanceUrl)
    }
    // SSH or scp-like. The host must match; the port is NOT compared, because the instance url
    // only ever carries an HTTP(S) port and GitLab's SSH port is independent of it (U7 fixed that
    // no setting supplies one). Comparing them would reject every SSH remote on a custom port.
    val remote = RemoteAuthorityParser.parse(remoteUrl) ?: return false
    val instance = RemoteAuthorityParser.parse(instanceUrl) ?: return false
    return remote.host == instance.host
  }

  /**
   * Hosts must match. Ports are compared only when both sides state one: an instance URL never
   * carries an SSH port and U7 fixed that no setting supplies one, so demanding a match would
   * refuse every legitimate SSH remote.
   */
  private fun authoritiesMatch(left: RemoteAuthority, right: RemoteAuthority): Boolean {
    if (left.host != right.host) return false
    if (left.port == null || right.port == null) return true
    return left.port == right.port
  }

  private fun pathOf(url: String): String {
    val trimmed = url.trim().trimEnd('/').removeSuffix(".git").trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    val path = if (schemeEnd > 0) {
      trimmed.substring(schemeEnd + "://".length).substringAfter('/', "")
    } else {
      trimmed.substringAfter(':', "")
    }
    return path.trim('/')
  }

  private companion object {
    const val HTTP_SCHEME_PREFIX = "http"
  }
}
