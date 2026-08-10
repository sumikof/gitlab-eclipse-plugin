package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.DiscussionMutationException
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabApiTimeoutException
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.NoteChangedException
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
  data class Definite(val cause: Throwable) : DiscussionWriteOutcome {
    /** 設計 §9.2。生成形は `cause.toString()`(クラス名 **と** message)を載せる。型だけ残す。 */
    override fun toString(): String = "DiscussionWriteOutcome.Definite(type=${cause.javaClass.name})"
  }

  data class Ambiguous(val cause: Throwable) : DiscussionWriteOutcome {
    /** 設計 §9.2。生成形は `cause.toString()`(クラス名 **と** message)を載せる。型だけ残す。 */
    override fun toString(): String = "DiscussionWriteOutcome.Ambiguous(type=${cause.javaClass.name})"
  }

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
 * Callers **must** rethrow `kotlinx.coroutines.CancellationException` before calling this
 * function, as [classifyWrite][com.gitlab.eclipse.ci.actions.classifyWrite] and
 * [DiscussionsLoader][com.gitlab.eclipse.mergerequests.discussions.DiscussionsLoader] already do.
 * This function deliberately does not special-case it: silently mapping a cancellation to an
 * outcome would hide a cancelled coroutine from its caller.
 *
 * The branch order is load-bearing:
 * - `java.net.http.HttpTimeoutException` **is an** `IOException` and is intentionally caught by
 *   the `IOException` branch (both classify Ambiguous), rather than given its own branch.
 * - [GitLabApiTimeoutException] and [JsonSyntaxException] have explicit branches for readability
 *   only; both would reach the same result (Ambiguous) via `else` if their branches were removed.
 * - The genuinely order-critical property is that no branch above the `IOException` branch is a
 *   supertype of `IOException`, and that the `else` default is always Ambiguous, never Definite:
 *   "Definite" means we can prove from the response that the mutation did not execute. If we
 *   cannot prove it, offering `[Retry]` would let the user post a duplicate comment.
 * - [GraphQlException] (L2) splits on `hasDataKey`: `data` key absent means the request never
 *   entered the execution phase, so it is Definite. `data` key present with errors means a
 *   partial success is possible, so it is Ambiguous.
 */
fun classifyWriteFailure(cause: Throwable): DiscussionWriteOutcome = when (cause) {
  is DiscussionMutationException -> DiscussionWriteOutcome.Definite(cause)
  // The edit pre-check (design §13) refused locally before any mutation was issued, so retrying
  // is provably safe: nothing was sent.
  is NoteChangedException -> DiscussionWriteOutcome.Definite(cause)
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
