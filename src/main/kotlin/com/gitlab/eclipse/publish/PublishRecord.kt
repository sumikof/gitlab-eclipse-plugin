package com.gitlab.eclipse.publish

import java.time.Instant

/**
 * What this plugin has recorded about publishing one repository (design §11).
 *
 * There is at most ONE record per repository and the [Intent] to [State] transition is a
 * replacement of that single value, never a delete followed by a write: two writes leave a window
 * in which a crash or a failed flush loses both, or keeps both (§11 R3-4).
 *
 * [instanceUrl] is required from the intent stage onwards (§11 R3-2). Without it, losing a response
 * on instance A and then switching to B would let the recovery path adopt B's project when B
 * happens to hold the same namespace and path.
 */
sealed interface PublishRecord {
  val repositoryRootPath: String
  val instanceUrl: String

  /**
   * Written BEFORE `POST /projects` (§9.6-6). Recording afterwards would leave "the project exists
   * on GitLab but nothing here knows" whenever the response is lost, and the next run would create
   * a second one.
   *
   * [recordedAt] is persisted because the recovery path compares it against a candidate project's
   * creation time (§9.6 recovery step 3 / R4-6).
   */
  data class Intent(
    override val repositoryRootPath: String,
    override val instanceUrl: String,
    val namespacePath: String,
    val projectPath: String,
    val recordedAt: Instant,
  ) : PublishRecord

  /**
   * Written once the project exists. Resume matching is on [normalizedRemoteUrl], not on
   * [remoteName] or [projectWebUrl] (§9.6 R2-4): an SSH remote's user, host and port cannot be
   * recovered from a web URL, and a loose path comparison would match the wrong repository.
   */
  data class State(
    override val repositoryRootPath: String,
    override val instanceUrl: String,
    val namespacePath: String,
    val projectPath: String,
    val projectId: Long,
    val normalizedRemoteUrl: String,
    val remoteName: String,
    val projectWebUrl: String,
  ) : PublishRecord
}
