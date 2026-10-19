package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.ci.actions.sameConfiguredInstance
import com.gitlab.eclipse.mergerequests.review.connectionMatches
import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CancellationException

/** Both sides of an MR file diff, ready for Compare; a side is `null` exactly when [sides] has none. */
data class MrFileDiffContent(
  val request: MrFileDiffRequest,
  val sides: MrDiffSides,
  val left: ClassifiedContent?,
  val right: ClassifiedContent?,
)

/** Result of one [MrFileDiffLoader.load]. */
sealed interface LoadOutcome {
  data class Ready(val content: MrFileDiffContent) : LoadOutcome

  data class Failed(val kind: DiffFailure) : LoadOutcome

  /** The caller cancelled (or the registry went inactive); report nothing. */
  object Cancelled : LoadOutcome
}

/**
 * Loads both sides of an MR file diff (REVIEW-01 design §5.1, §7). Background thread only: it
 * captures the connection (which may refresh OAuth synchronously) and reads JGit / REST.
 *
 * The connection gate refuses to send the token anywhere but the instance and account the sidebar
 * list came from; the instance is compared before the credential is even read (`captureConnectionIf`).
 * Either side failing fails the whole diff — a half-filled Compare is never shown.
 * [load] checks `isCancelled` before and after each side. It never throws except to propagate
 * cancellation: anything unexpected folds into [DiffFailure.GENERIC] and is logged as the outcome
 * and the exception's class name only — never its message, a path, a SHA or a URL (design §9).
 */
class MrFileDiffLoader(
  private val captureConnectionIf: (acceptInstanceUrl: (String) -> Boolean) -> ConnectionSnapshot?,
  private val source: MrFileContentSource,
) {
  fun load(request: MrFileDiffRequest, isCancelled: () -> Boolean): LoadOutcome = try {
    loadSides(request, isCancelled)
  } catch (e: CancellationException) {
    throw e
  } catch (_: UnstableConnectionException) {
    LoadOutcome.Failed(DiffFailure.UNSTABLE_CONNECTION)
  } catch (_: GitLabConfigurationException) {
    LoadOutcome.Failed(DiffFailure.CONFIG)
  } catch (e: Throwable) {
    logFailure(DiffFailure.GENERIC, e)
    LoadOutcome.Failed(DiffFailure.GENERIC)
  }

  private fun loadSides(request: MrFileDiffRequest, isCancelled: () -> Boolean): LoadOutcome {
    val sides = MrDiffSides.of(request)
    if (sides.submodule) return LoadOutcome.Failed(DiffFailure.SUBMODULE)
    // The instance is checked before the credential is read (#49), so a changed instance never
    // triggers a token refresh; the account is checked on the captured snapshot.
    val connection = captureConnectionIf { sameConfiguredInstance(request.instanceUrl, it) }
    if (connection == null || !connectionMatches(request.instanceUrl, request.authFingerprint, connection)) {
      return LoadOutcome.Failed(DiffFailure.CONNECTION_CHANGED)
    }
    if (isCancelled()) return LoadOutcome.Cancelled
    val left = sides.left?.let { source.read(request.projectId, it, connection) }
    if (left is SideContent.Failed) return LoadOutcome.Failed(left.kind)
    if (isCancelled()) return LoadOutcome.Cancelled
    val right = sides.right?.let { source.read(request.projectId, it, connection) }
    if (right is SideContent.Failed) return LoadOutcome.Failed(right.kind)
    if (isCancelled()) return LoadOutcome.Cancelled
    val leftBytes = (left as? SideContent.Bytes)?.bytes
    val rightBytes = (right as? SideContent.Bytes)?.bytes
    val forceBinary = listOfNotNull(leftBytes, rightBytes).any(ContentClassifier::looksBinary)
    return LoadOutcome.Ready(
      MrFileDiffContent(
        request = request,
        sides = sides,
        left = leftBytes?.let { classify(sides.left, it, forceBinary) },
        right = rightBytes?.let { classify(sides.right, it, forceBinary) },
      ),
    )
  }

  private fun classify(side: SideRef?, bytes: ByteArray, forceBinary: Boolean): ClassifiedContent =
    ContentClassifier.classify(side?.path.orEmpty().substringAfterLast('/'), bytes, forceBinary)

  /** Outcome and exception class only; the logger lookup itself must not fail the load. */
  private fun logFailure(kind: DiffFailure, error: Throwable) {
    runCatching {
      logger<MrFileDiffLoader>().warn("$LOAD_FAILED_MESSAGE outcome=$kind exceptionType=${error.javaClass.name}")
    }
  }

  private companion object {
    const val LOAD_FAILED_MESSAGE = "MR file diff load failed:"
  }
}
