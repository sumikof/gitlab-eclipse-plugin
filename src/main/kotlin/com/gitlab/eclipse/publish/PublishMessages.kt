package com.gitlab.eclipse.publish

/**
 * Turns a [PublishOutcome] or a [Preflight.Stop] into what the user is told (design §13 / §16).
 *
 * Separate from the handler so it can be tested headless. Counts and paths follow the same rule as
 * everywhere else — nothing here quotes a filesystem path or a JGit message — with **one deliberate
 * exception**: [PublishOutcome.PushFailed] includes the remote URL, because §12 requires disclosing
 * it. The project exists at that point and the URL is what the user needs to finish by hand.
 */
object PublishMessages {
  fun of(outcome: PublishOutcome): String = when (outcome) {
    is PublishOutcome.Published ->
      "GitLab: Published. The project is at ${outcome.webUrl}"
    is PublishOutcome.PushFailed -> buildString {
      append("GitLab: The project was created at ${outcome.webUrl}, but the push failed")
      append(if (outcome.rejected) " because the server rejected it." else ".")
      append(" The remote is set to ${outcome.remoteUrl} — run this command again to retry the push,")
      append(" or push by hand. The project is NOT created a second time.")
    }
    is PublishOutcome.Blocked -> of(outcome.reason)
    PublishOutcome.Busy ->
      "GitLab: Another git operation is running on this repository. Try again when it finishes."
    PublishOutcome.RecordPersistenceFailed ->
      "GitLab: Could not save the publish record, so nothing was created. Check the Error Log."
    PublishOutcome.InstanceMismatch ->
      "GitLab: The configured GitLab instance did not match. Nothing was created."
    is PublishOutcome.Failed -> "GitLab: Could not publish. See the Error Log."
  }

  fun of(reason: Preflight.Stop): String = when (reason) {
    Preflight.Stop.REMOTE_EXISTS ->
      "GitLab: This repository already has a remote, so it was left alone."
    Preflight.Stop.DETACHED_HEAD ->
      "GitLab: HEAD is detached, so there is no branch to push. Check out a branch first."
    Preflight.Stop.NOT_A_DIRECTORY -> "GitLab: Select a folder to publish."
    Preflight.Stop.NO_FILES -> "GitLab: There is nothing to publish in that folder."
    Preflight.Stop.INSTANCE_MISMATCH ->
      "GitLab: This folder was published to a different GitLab instance. Switch back to it to continue."
  }
}
