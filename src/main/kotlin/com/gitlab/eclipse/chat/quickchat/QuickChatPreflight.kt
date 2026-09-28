package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import com.gitlab.eclipse.navigation.ProjectResolution
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.runInterruptible
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decides, once per conversation binding, whether a question about the anchor file may be sent and
 * with which `resourceId` (design §9.2.2): resolves the file's project on every send, reuses the
 * bound preflight when the project identity is unchanged, and otherwise asks GitLab for its version and
 * — for a project file — the project's id and Duo setting. Fails closed: when the project cannot be
 * confirmed, nothing is sent.
 *
 * Returns a value; storing it is the UI thread's job. Q1 transport failures are thrown for the
 * caller to classify (they must become `TransportFailed` without a saved preflight).
 */
class QuickChatPreflight(
  private val api: QuickChatApi,
  private val resolveProject: (File?) -> ProjectResolution,
) {
  private val logger by lazy { logger<QuickChatPreflight>() }

  sealed interface Result {
    /** Send with [preflight]; [projectChanged] means the bound conversation must not be continued. */
    data class Proceed(val preflight: Preflight, val projectChanged: Boolean) : Result

    /** Do not send; [outcome] is the send's result. */
    data class Stop(val outcome: QuickChatOutcome) : Result
  }

  /** [connection] is the one this send uses; [binding] is the conversation's, if any (same instance). */
  suspend fun check(
    connection: ConnectionSnapshot,
    anchorFile: File?,
    binding: ConversationBinding?,
    budget: RequestBudget,
  ): Result {
    val identity = when (val k = identityOf(connection, resolve(anchorFile))) {
      is IdentityOrStop.Resolved -> k.identity
      is IdentityOrStop.Stop -> return k.stop
    }
    if (binding != null && binding.preflight.project == identity) {
      return Result.Proceed(binding.preflight, projectChanged = false)
    }
    val projectChanged = binding != null

    val versionTimeout = budget.nextTimeout() ?: return timedOut()
    val version = runInterruptible { api.version(connection, versionTimeout) }
    // An unparsable or missing version continues, as the reference does (§9.2.2 step 3).
    if (GitLabVersion.parse(version)?.supportsQuickChat() == false) {
      return Result.Stop(QuickChatOutcome.Unsupported(version))
    }

    val fullPath = identity.fullPath ?: return Result.Proceed(Preflight(null, identity), projectChanged)
    val projectTimeout = budget.nextTimeout() ?: return timedOut()
    val project = runInterruptible { api.project(connection, fullPath, projectTimeout) }
      ?: return Result.Stop(QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.PROJECT_NOT_FOUND))
    if (project.duoFeaturesEnabled == false) {
      return Result.Stop(QuickChatOutcome.Unavailable(QuickChatOutcome.Unavailable.DUO_DISABLED_FOR_PROJECT))
    }
    // null duoFeaturesEnabled: the server enforces the project setting itself, since resourceId is sent.
    return Result.Proceed(Preflight(project.id, identity), projectChanged)
  }

  private suspend fun resolve(anchorFile: File?): ProjectResolution = try {
    runInterruptible { resolveProject(anchorFile) }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    logger.warn("Quick Chat project resolution failed: ${e.javaClass.name}")
    ProjectResolution.Failed
  }

  private fun identityOf(connection: ConnectionSnapshot, resolution: ProjectResolution): IdentityOrStop = when (resolution) {
    ProjectResolution.NotInRepository -> IdentityOrStop.Resolved(ProjectIdentity.NOT_IN_REPOSITORY)
    ProjectResolution.NoGitLabRemote -> IdentityOrStop.Resolved(ProjectIdentity.NO_GITLAB_REMOTE)
    ProjectResolution.Failed -> IdentityOrStop.Stop(projectCheckFailed(ProjectCheckKind.RESOLUTION_FAILED))
    is ProjectResolution.Resolved -> {
      val instanceUrl = normalizeInstanceUrl(resolution.project.instanceUrl)
      val fullPath = decodeFullPath(resolution.project.namespaceWithPath)
      when {
        // Another instance's project setting cannot be checked through this connection.
        instanceUrl != normalizeInstanceUrl(connection.instanceUrl) ->
          IdentityOrStop.Stop(projectCheckFailed(ProjectCheckKind.OTHER_INSTANCE))
        // A malformed escape must not be guessed at: it could name a different project (A25).
        fullPath == null -> IdentityOrStop.Stop(projectCheckFailed(ProjectCheckKind.RESOLUTION_FAILED))
        else -> IdentityOrStop.Resolved(ProjectIdentity.resolved(instanceUrl, fullPath))
      }
    }
  }

  private fun projectCheckFailed(kind: ProjectCheckKind) = Result.Stop(QuickChatOutcome.ProjectCheckFailed(kind))

  private fun timedOut() = Result.Stop(QuickChatOutcome.TimedOut(beforeSend = true, update = null))

  private sealed interface IdentityOrStop {
    data class Resolved(val identity: ProjectIdentity) : IdentityOrStop
    data class Stop(val stop: Result.Stop) : IdentityOrStop
  }
}
