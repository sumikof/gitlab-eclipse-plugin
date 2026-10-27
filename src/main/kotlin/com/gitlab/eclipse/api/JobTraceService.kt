package com.gitlab.eclipse.api

import com.gitlab.eclipse.ci.joblog.MAX_DISPLAY_RAW_BYTES
import com.gitlab.eclipse.ci.joblog.RawTraceWriter
import com.gitlab.eclipse.inject.service
import java.nio.file.Path

/**
 * Fetches a single job's raw trace log, pinned to a [ConnectionSnapshot] (design §8.1). Pure path
 * assembly + delegation to [GitLabApiClient.fetchText]; no formatting (see stripTraceFormatting) and
 * no exception handling here — exceptions from the API client propagate to the caller.
 */
class JobTraceService(private val apiClient: GitLabApiClient = service()) {
  fun getTrace(projectId: Long, jobId: Long, connection: ConnectionSnapshot): String =
    apiClient.fetchText("/projects/$projectId/jobs/$jobId/trace", connection)

  /**
   * Fetches the last [maxBytes] of the trace. A non-null [etag] is sent as `If-None-Match` unchanged (weak
   * validators included); a 304 gives [TraceFetch.NotModified].
   */
  fun getTraceSnapshot(
    projectId: Long,
    jobId: Long,
    etag: String?,
    connection: ConnectionSnapshot,
    maxBytes: Int = MAX_DISPLAY_RAW_BYTES,
  ): TraceFetch = apiClient.fetchTrace("/projects/$projectId/jobs/$jobId/trace", etag, maxBytes, connection)

  /**
   * Streams the whole raw trace into [target] (atomically, via a temporary file next to it). Once [isCancelled] says
   * yes the copy stops with a `CancellationException` and [target] is left as it was. [onOpened] gets the function
   * that closes the body at once (see [GitLabApiClient.downloadTo]).
   */
  fun downloadRawTrace(
    projectId: Long,
    jobId: Long,
    connection: ConnectionSnapshot,
    target: Path,
    onOpened: (closeNow: () -> Unit) -> Unit = {},
    isCancelled: () -> Boolean = { false },
  ) {
    RawTraceWriter.write(target) { out ->
      apiClient.downloadTo("/projects/$projectId/jobs/$jobId/trace", connection, onOpened) { body ->
        RawTraceWriter.copy(body, out, isCancelled)
      }
    }
  }
}
