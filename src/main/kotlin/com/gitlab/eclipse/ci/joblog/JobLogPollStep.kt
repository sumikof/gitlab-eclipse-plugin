package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.api.model.GitLabJob
import com.google.gson.JsonParseException
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.coroutines.cancellation.CancellationException

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** Immutable input of one trace poll, built on the UI thread. Not a data class (the cursor holds byte arrays). */
class TraceSnapshot(val etag: String?, val cursor: TraceCursor?, val state: RenderState?, val runs: StyleRunIndex)

sealed interface JobLogUpdate {
  /**
   * Replace the document from [replaceFrom] to its end with [text]; [runs] is the WHOLE new index;
   * [newFolds] only the new ones.
   */
  class Append(
    val replaceFrom: Int,
    val text: String,
    val runs: StyleRunIndex,
    val newFolds: List<FoldRegion>,
  ) : JobLogUpdate

  class Full(val result: RenderResult) : JobLogUpdate
}

sealed interface TraceStepResult {
  class Changed(
    val update: JobLogUpdate,
    val cursor: TraceCursor,
    val state: RenderState,
    val etag: String?,
    val totalBytes: Long,
  ) : TraceStepResult

  class Unchanged(val etag: String?, val totalBytes: Long) : TraceStepResult

  class Failed(val failure: PollFailure) : TraceStepResult
}

sealed interface StatusStepResult {
  data class Seen(val status: String?, val hasTrace: Boolean, val erased: Boolean) : StatusStepResult

  data class Failed(val failure: PollFailure) : StatusStepResult
}

/** Why a poll failed, without any URL, response body or message ([outcome] names the audit outcome). */
data class PollFailure(
  val kind: PollFailureKind,
  val outcome: String,
  val httpStatus: Int? = null,
  val correlationId: String? = null,
  val exceptionClass: String? = null,
)

/**
 * IO thread. [fetch] is the trace GET sent with the given If-None-Match. Turns the answer into a document update;
 * [CancellationException] is rethrown, every other exception becomes [TraceStepResult.Failed].
 */
fun traceStep(
  snapshot: TraceSnapshot,
  fetch: (etag: String?) -> TraceFetch,
  hardLimitBytes: Long = HARD_DISPLAY_RAW_BYTES,
): TraceStepResult = try {
  traceResult(snapshot, fetch(snapshot.etag), hardLimitBytes)
} catch (e: Exception) {
  TraceStepResult.Failed(classifyPollFailure(e) ?: throw e)
}

private fun traceResult(snapshot: TraceSnapshot, reply: TraceFetch, limit: Long): TraceStepResult = when (reply) {
  TraceFetch.NotModified ->
    if (snapshot.etag == null) {
      TraceStepResult.Failed(PollFailure(PollFailureKind.TRANSIENT, "notModified"))
    } else {
      TraceStepResult.Unchanged(snapshot.etag, snapshot.cursor?.totalBytes ?: 0L)
    }
  is TraceFetch.Body -> when (val delta = traceDelta(snapshot.cursor, reply, limit)) {
    TraceDelta.Unchanged -> TraceStepResult.Unchanged(reply.etag, reply.totalBytes)
    is TraceDelta.Appended -> appended(snapshot, reply, delta.bytes) ?: full(reply)
    is TraceDelta.Full -> full(reply)
  }
}

/** Null when the render state does not line up with the cursor (the caller then rebuilds the document). */
private fun appended(snapshot: TraceSnapshot, body: TraceFetch.Body, bytes: ByteArray): TraceStepResult? {
  val cursor = snapshot.cursor ?: return null
  val state = snapshot.state ?: return null
  if (state.confirmedBytes + state.pendingRaw.size != cursor.totalBytes) return null
  val delta = appendRender(state, bytes)
  val runs = snapshot.runs.appendFrom(delta.replaceFrom, delta.runs)
  val update = JobLogUpdate.Append(delta.replaceFrom, delta.text, runs, delta.newFolds)
  val next = cursorAfter(body, cursor.displayStartBytes)
  return TraceStepResult.Changed(update, next, delta.state, body.etag, body.totalBytes)
}

private fun full(body: TraceFetch.Body): TraceStepResult {
  val (result, state) = renderInitial(body.bytes, body.omittedBytes)
  val next = cursorAfter(body, body.omittedBytes)
  return TraceStepResult.Changed(JobLogUpdate.Full(result), next, state, body.etag, body.totalBytes)
}

/** IO thread. [CancellationException] is rethrown, every other exception becomes [StatusStepResult.Failed]. */
fun statusStep(fetch: () -> GitLabJob): StatusStepResult = try {
  val job = fetch()
  StatusStepResult.Seen(job.status, hasTrace(job.startedAt, job.erasedAt), job.erasedAt != null)
} catch (e: Exception) {
  StatusStepResult.Failed(classifyPollFailure(e) ?: throw e)
}

/** Null means "rethrow" ([CancellationException]). Neither the response body nor the exception message is kept. */
fun classifyPollFailure(e: Exception): PollFailure? = when (e) {
  is CancellationException -> null
  is GitLabApiException -> PollFailure(httpKind(e.statusCode), "http", e.statusCode, e.correlationId)
  is HttpTimeoutException -> PollFailure(PollFailureKind.TRANSIENT, "timeout")
  is IOException -> PollFailure(PollFailureKind.TRANSIENT, "io")
  is JsonParseException -> PollFailure(PollFailureKind.TRANSIENT, "parse", exceptionClass = e.javaClass.simpleName)
  else -> PollFailure(PollFailureKind.TRANSIENT, "unexpected", exceptionClass = e.javaClass.simpleName)
}

private fun httpKind(status: Int): PollFailureKind = when (status) {
  HTTP_NOT_FOUND -> PollFailureKind.NOT_FOUND
  HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> PollFailureKind.DENIED
  else -> PollFailureKind.TRANSIENT
}
