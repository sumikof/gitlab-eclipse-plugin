package com.gitlab.eclipse.assignments

/**
 * A repository the user has pointed at a specific GitLab project (design §11).
 *
 * [instanceUrl] is not optional (§11.1). The assignment is consulted BEFORE the normal resolution,
 * which is where the "does this remote belong to the configured instance" check lives — so without
 * recording which instance the assignment was made against, switching instances would keep using
 * it, and a same-named project on the other instance would silently receive the writes.
 *
 * [remoteUrl] is recorded for the same reason in the other direction: it is what proves the
 * assignment still describes this repository, and it is compared against the remotes that actually
 * exist rather than trusted on its own.
 */
data class ProjectAssignment(
  val repositoryRootPath: String,
  val remoteUrl: String,
  val instanceUrl: String,
  val namespaceWithPath: String,
  val projectId: Long,
)
