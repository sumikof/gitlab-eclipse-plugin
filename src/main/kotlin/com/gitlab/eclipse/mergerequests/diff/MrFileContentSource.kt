package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.BoundedBytes
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.utils.logger
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException

/** Per-side content cap (design §8): a side larger than this is not compared. */
const val MAX_SIDE_BYTES = 4 * 1024 * 1024

/** Why an MR file diff could not be shown; each kind maps to one notification (design §9). */
enum class DiffFailure {
  CONNECTION_CHANGED,
  UNSTABLE_CONNECTION,
  CONFIG,
  UNAUTHORIZED,
  NOT_FOUND_OR_DENIED,
  RATE_LIMITED,
  TIMED_OUT,
  TOO_LARGE,
  SUBMODULE,
  INVALID,
  GENERIC,
}

/** The content of one diff side, or why it could not be read. */
sealed interface SideContent {
  class Bytes(val bytes: ByteArray) : SideContent

  data class Failed(val kind: DiffFailure) : SideContent
}

/** Reads one diff side of a file in [projectId], pinned to [connection]. Background thread only. */
fun interface MrFileContentSource {
  fun read(projectId: Long, side: SideRef, connection: ConnectionSnapshot): SideContent
}

/**
 * Local object database first, REST raw second (design §5.1, N5): both are addressed by SHA, so the
 * two sources cannot disagree and mixing them across sides is safe. A commit missing locally
 * (shallow, partial, fork, or no clone) or a path missing from a local commit falls back to REST; a
 * local size-cap hit or gitlink is final. No retry anywhere (design §9).
 */
class HybridMrFileContentSource(
  private val local: LocalBlobReader,
  private val remote: RawFileFetcher,
  private val maxBytes: Int = MAX_SIDE_BYTES,
) : MrFileContentSource {
  override fun read(projectId: Long, side: SideRef, connection: ConnectionSnapshot): SideContent =
    when (val blob = local.read(side, maxBytes)) {
      is LocalBlob.Found -> SideContent.Bytes(blob.bytes)
      LocalBlob.TooLarge -> SideContent.Failed(DiffFailure.TOO_LARGE)
      LocalBlob.Gitlink -> SideContent.Failed(DiffFailure.SUBMODULE)
      LocalBlob.NotInTree, LocalBlob.Unavailable -> readRemote(projectId, side, connection)
    }

  private fun readRemote(projectId: Long, side: SideRef, connection: ConnectionSnapshot): SideContent = try {
    when (val bytes = remote.fetch(projectId, side, maxBytes, connection)) {
      is BoundedBytes.Ok -> SideContent.Bytes(bytes.bytes)
      BoundedBytes.TooLarge -> SideContent.Failed(DiffFailure.TOO_LARGE)
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: GitLabApiException) {
    SideContent.Failed(failureFor(e.statusCode))
  } catch (_: HttpTimeoutException) {
    SideContent.Failed(DiffFailure.TIMED_OUT)
  } catch (_: UnstableConnectionException) {
    SideContent.Failed(DiffFailure.UNSTABLE_CONNECTION)
  } catch (_: GitLabConfigurationException) {
    SideContent.Failed(DiffFailure.CONFIG)
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    SideContent.Failed(DiffFailure.GENERIC)
  } catch (e: Exception) {
    logUnexpected(e)
    SideContent.Failed(DiffFailure.GENERIC)
  }

  /** Class name only (TLS, proxy, I/O, …) so support can tell causes apart; never the message (design §9). */
  private fun logUnexpected(error: Exception) {
    runCatching {
      logger<HybridMrFileContentSource>()
        .warn("MR file diff side read failed: outcome=GENERIC exceptionType=${error.javaClass.name}")
    }
  }

  private fun failureFor(status: Int): DiffFailure =
    when (status) {
      HTTP_UNAUTHORIZED -> DiffFailure.UNAUTHORIZED
      HTTP_FORBIDDEN, HTTP_NOT_FOUND -> DiffFailure.NOT_FOUND_OR_DENIED
      HTTP_TOO_MANY_REQUESTS -> DiffFailure.RATE_LIMITED
      else -> DiffFailure.GENERIC
    }

  private companion object {
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_FORBIDDEN = 403
    const val HTTP_NOT_FOUND = 404
    const val HTTP_TOO_MANY_REQUESTS = 429
  }
}
