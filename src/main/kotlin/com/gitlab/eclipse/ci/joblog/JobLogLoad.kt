package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.TraceFetch
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

private const val HTTP_NOT_FOUND = 404

/** Outcome of one "Display Log" read: the rendered log, or a failure classified like [TraceResult.Failed]. */
sealed interface JobLogLoad {
  data class Rendered(val result: RenderResult) : JobLogLoad

  data class Failed(val failure: TraceResult.Failed) : JobLogLoad
}

/**
 * Runs [fetch] (the trace GET, unconditional: no ETag) and [render] (`renderInitial`) and
 * classifies the outcome the same way as [fetchAndFormatTrace]. [CancellationException] is
 * RETHROWN; any OTHER unexpected throwable propagates to the handler's terminal catch so the
 * "unexpected" audit stays in one place. A 304 cannot answer a request without `If-None-Match`,
 * so [TraceFetch.NotModified] here is a failure of its own ("notModified").
 */
fun fetchAndRenderTrace(fetch: () -> TraceFetch, render: (ByteArray, Long) -> RenderResult): JobLogLoad =
  try {
    when (val fetched = fetch()) {
      is TraceFetch.Body -> JobLogLoad.Rendered(render(fetched.bytes, fetched.omittedBytes))
      TraceFetch.NotModified -> failed("notModified")
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: GitLabApiException) {
    JobLogLoad.Failed(
      TraceResult.Failed("http", e.statusCode, e.correlationId, notCommittal = e.statusCode == HTTP_NOT_FOUND),
    )
  } catch (ignored: HttpTimeoutException) {
    failed("timeout")
  } catch (ignored: IOException) {
    failed("io")
  }

private fun failed(kind: String) = JobLogLoad.Failed(TraceResult.Failed(kind, null, null, notCommittal = false))
