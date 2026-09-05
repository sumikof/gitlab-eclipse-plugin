package com.gitlab.eclipse.publish

import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.api.ProjectCreationService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import java.time.Duration
import java.time.Instant

/** What to do with an unconfirmed [PublishRecord.Intent] (design §9.6 recovery). */
sealed interface Recovery {
  /** The project provably came from the previous run: resume at the push without asking. */
  data class Adopt(val state: PublishRecord.State) : Recovery

  /**
   * A project occupies the path but ownership could not be established. The caller MUST get
   * explicit consent before pushing; without it, discard the intent and run the normal flow.
   */
  data class Confirm(val state: PublishRecord.State, val webUrl: String) : Recovery

  /** Nothing is there, so the previous POST never created anything: drop the intent. */
  data object Discard : Recovery
}

/**
 * Decides whether a project found at the intent's path may be adopted (design §9.6 recovery).
 *
 * "The path exists" is explicitly NOT proof that the previous run created it (R3-3): the POST may
 * have failed on a path collision, or another user may have taken the path while the response was
 * in flight. Adopting on existence alone would push this repository into someone else's project.
 *
 * Adoption therefore requires BOTH that the current user is the creator AND that the creation time
 * sits near [Intent.recordedAt]. The time test never stands alone — server clocks are not
 * trustworthy — and anything short of both drops to [Recovery.Confirm], because the design's rule
 * is to prefer asking over adopting wrongly.
 *
 * Blocking network I/O — call from a background thread.
 */
class PublishIntentRecovery(
  private val projects: ProjectCreationService = service(),
  private val users: CurrentUserService = service(),
  private val now: () -> Instant = Instant::now,
) {
  private val logger by lazy { logger<PublishIntentRecovery>() }

  fun decide(intent: PublishRecord.Intent, remoteName: String): Recovery {
    val accept: (String) -> Boolean = { candidate -> candidate == intent.instanceUrl }
    val namespaceWithPath = listOf(intent.namespacePath, intent.projectPath)
      .filter { it.isNotBlank() }
      .joinToString("/")
    val found = projects.findProject(namespaceWithPath, accept) ?: return Recovery.Discard

    // HTTPS on purpose: the intent does not record which connection type was chosen, and a
    // token-authenticated HTTPS remote works wherever this plugin can already reach the API,
    // whereas SSH depends on key material we cannot verify from here.
    val state = PublishRecord.State(
      repositoryRootPath = intent.repositoryRootPath,
      instanceUrl = intent.instanceUrl,
      namespacePath = intent.namespacePath,
      projectPath = intent.projectPath,
      projectId = found.id,
      normalizedRemoteUrl = RemoteUrlNormalizer.normalize(found.httpUrl),
      remoteName = remoteName,
      projectWebUrl = found.webUrl,
    )
    return if (createdByUsAtAboutTheRightTime(found.creatorId, found.createdAt, intent.recordedAt)) {
      Recovery.Adopt(state)
    } else {
      Recovery.Confirm(state, found.webUrl)
    }
  }

  private fun createdByUsAtAboutTheRightTime(
    creatorId: Long?,
    createdAt: Instant?,
    recordedAt: Instant,
  ): Boolean {
    if (creatorId == null || createdAt == null) return false
    val currentUserId = try {
      users.getCurrentUser().id
    } catch (e: Exception) {
      // Cannot establish ownership: fall through to asking. Type only (A9).
      logger.info("Current user lookup for publish recovery failed: ${e.javaClass.name}")
      return false
    }
    if (creatorId != currentUserId) return false
    // A window either side of the record, wide enough for client/server clock skew and narrow
    // enough that an unrelated project of the same name is unlikely to sit inside it.
    return !createdAt.isBefore(recordedAt.minus(CLOCK_SKEW)) && !createdAt.isAfter(now().plus(CLOCK_SKEW))
  }

  private companion object {
    /** Fixed skew allowance; see the implementation plan for F6. */
    val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
  }
}
