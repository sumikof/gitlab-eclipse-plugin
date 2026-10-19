package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.BoundedBytes
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import java.nio.charset.StandardCharsets

/**
 * Fetches one side of an MR file diff from GitLab's raw file endpoint,
 * `GET /projects/:id/repository/files/:path/raw?ref=:sha` (REVIEW-01 design §5.1), pinned to the
 * caller's [ConnectionSnapshot] and capped at `maxBytes`. The project is the merge request's target
 * project, as in VS Code (`changed_file_item.ts:55-60`).
 */
class RawFileFetcher(private val api: GitLabApiClient) {
  /** Raw bytes of [side] in [projectId], or [BoundedBytes.TooLarge]; HTTP failures propagate. */
  fun fetch(projectId: Long, side: SideRef, maxBytes: Int, connection: ConnectionSnapshot): BoundedBytes =
    api.fetchBytes(
      "/projects/$projectId/repository/files/${encodePath(side.path)}/raw",
      mapOf("ref" to side.sha),
      maxBytes,
      connection,
    )

  companion object {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val BYTE_MASK = 0xFF

    /**
     * Percent-encodes every UTF-8 byte of [path] outside the RFC 3986 unreserved set, so `/` becomes
     * `%2F` (GitLab takes the whole file path as one segment) and space `%20` — not `+`.
     */
    fun encodePath(path: String): String =
      path.toByteArray(StandardCharsets.UTF_8).joinToString("") { byte ->
        val char = (byte.toInt() and BYTE_MASK).toChar()
        if (char in UNRESERVED) char.toString() else "%%%02X".format(byte.toInt() and BYTE_MASK)
      }
  }
}
