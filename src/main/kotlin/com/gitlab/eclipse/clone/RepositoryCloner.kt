package com.gitlab.eclipse.clone

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.GitAuthConfigurer
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CanceledException
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** JGit's transport timeout, in seconds (`TransportCommand.setTimeout` is second-granular). */
private const val TRANSPORT_TIMEOUT_SECONDS = 30

/**
 * Runs `git clone` and nothing else.
 *
 * This class has NO delete path, by design: whatever a failed clone leaves behind stays where it
 * is as far as the plugin is concerned. (JGit's own `CloneCommand.cleanup()` still deletes on the
 * handled-failure path — that is outside this plugin's control and is an accepted limitation, not
 * something to compensate for here.)
 *
 * The timeout matters as much as the monitor: a connection that never delivers a progress
 * callback (no route, a black-holing peer) cannot be escaped through `isCancelled()` at all, so
 * `setTimeout` is the only thing that ends it.
 */
class RepositoryCloner(
  private val guard: GitOperationGuard = service(),
  private val applyAuth: (CloneCommand, String) -> CloneCommand =
    { command, instanceUrl -> GitAuthConfigurer().applyAuth(command, instanceUrl) },
) {
  private val logger by lazy { logger<RepositoryCloner>() }

  sealed interface Outcome {
    data object Succeeded : Outcome

    data object Cancelled : Outcome

    /** Another clone into the same filesystem location is running; nothing was attempted. */
    data object Busy : Outcome

    /** [type] is the exception's type name only — JGit messages can carry the remote url. */
    data class Failed(val type: String) : Outcome
  }

  fun clone(cloneUrl: String, destination: File, instanceUrl: String, monitor: IProgressMonitor): Outcome {
    val key = CloneGuardKey.of(destination)
    return guard.withRepo(key) { runClone(cloneUrl, destination, instanceUrl, monitor) } ?: Outcome.Busy
  }

  @Suppress("TooGenericExceptionCaught")
  private fun runClone(
    cloneUrl: String,
    destination: File,
    instanceUrl: String,
    monitor: IProgressMonitor,
  ): Outcome =
    try {
      val command = Git.cloneRepository()
        .setURI(cloneUrl)
        .setDirectory(destination)
        .setProgressMonitor(EclipseProgressMonitorAdapter(monitor))
      command.setTimeout(TRANSPORT_TIMEOUT_SECONDS)
      applyAuth(command, instanceUrl)
      command.call().close() // the returned Git holds an open repository handle
      if (monitor.isCanceled) Outcome.Cancelled else Outcome.Succeeded
    } catch (e: CancellationException) {
      throw e
    } catch (_: CanceledException) {
      Outcome.Cancelled
    } catch (e: Exception) {
      logger.error("Clone failed: ${e.javaClass.name}")
      Outcome.Failed(e.javaClass.name)
    }
}
