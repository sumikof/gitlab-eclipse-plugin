package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.DiscussionMutationException
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabApiTimeoutException
import com.gitlab.eclipse.api.GraphQlException
import com.google.gson.JsonSyntaxException
import java.io.IOException

/**
 * Outcome of one discussion write attempt (design §12.2). [Definite] means the response proves
 * the mutation did not execute, so the caller may safely offer `[Retry]`. [Ambiguous] means the
 * mutation may have committed even though the caller observed a failure, so retrying risks a
 * duplicate comment. [GateRejected] is a connection-gate rejection (drives a text-preserving
 * dialog); [Aborted] is a pre-send lifecycle rejection (must produce no UI at all). These two are
 * distinct events and are never collapsed into one another.
 */
sealed interface DiscussionWriteOutcome {
  object Success : DiscussionWriteOutcome
  data class Definite(val cause: Throwable) : DiscussionWriteOutcome
  data class Ambiguous(val cause: Throwable) : DiscussionWriteOutcome
  object GateRejected : DiscussionWriteOutcome
  object Aborted : DiscussionWriteOutcome
}

private const val CLIENT_ERROR_MIN = 400
private const val CLIENT_ERROR_MAX = 499

/**
 * Classifies a write failure as [DiscussionWriteOutcome.Definite] (provably did not execute,
 * `[Retry]` is safe) or [DiscussionWriteOutcome.Ambiguous] (cannot be proven either way,
 * `[Retry]` risks a duplicate comment) (design §12.2).
 *
 * The branch order is load-bearing:
 * - `HttpTimeoutException` **is an** `IOException`, so the timeout branches must be checked
 *   before the `IOException` branch, or a timeout would be misclassified by the wrong branch
 *   (here both classify Ambiguous, but the order still documents the subtype relationship for
 *   any future branch that would depend on it).
 * - The unknown default (`else`) is always Ambiguous, never Definite: "Definite" means we can
 *   prove from the response that the mutation did not execute. If we cannot prove it, offering
 *   `[Retry]` would let the user post a duplicate comment.
 * - [GraphQlException] (L2) splits on `hasDataKey`: `data` key absent means the request never
 *   entered the execution phase, so it is Definite. `data` key present with errors means a
 *   partial success is possible, so it is Ambiguous.
 */
fun classifyWriteFailure(cause: Throwable): DiscussionWriteOutcome = when (cause) {
  is DiscussionMutationException -> DiscussionWriteOutcome.Definite(cause)
  is GraphQlException ->
    if (cause.hasDataKey) DiscussionWriteOutcome.Ambiguous(cause) else DiscussionWriteOutcome.Definite(cause)
  is GitLabApiException ->
    if (cause.statusCode in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX) {
      DiscussionWriteOutcome.Definite(cause)
    } else {
      DiscussionWriteOutcome.Ambiguous(cause)
    }
  is GitLabApiTimeoutException -> DiscussionWriteOutcome.Ambiguous(cause)
  is JsonSyntaxException -> DiscussionWriteOutcome.Ambiguous(cause)
  is IOException -> DiscussionWriteOutcome.Ambiguous(cause)
  else -> DiscussionWriteOutcome.Ambiguous(cause)
}
