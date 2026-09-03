package com.gitlab.eclipse.clone

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.model.GitLabProject
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.PathSegmentEncoder
import kotlinx.coroutines.CancellationException

private const val HTTP_NOT_FOUND = 404

/**
 * Resolves the HTTPS clone url of a GitLab project, shared by F5 (wiki) and F7 (repository).
 *
 * Talks to [GitLabApiClient] directly rather than through `ProjectDetailService` so the
 * connection can be captured ONCE and returned to the caller: the clone that follows has to
 * authenticate against the same instance this lookup used, and `ProjectDetailService` takes no
 * snapshot (it falls through to the ungated `captureConnection()`, issue #49).
 *
 * The whole namespace path goes through [PathSegmentEncoder.encodeSegment], which encodes `/`
 * as well — `encodePath` keeps `/` and would address a different endpoint.
 */
class CloneTargetLookup(private val apiClient: GitLabApiClient = service()) {

  sealed interface Result {
    /** [instanceUrl] is the pinned snapshot url; pass it to `GitAuthConfigurer.applyAuth`. */
    data class Ok(val httpUrlToRepo: String, val instanceUrl: String) : Result
    data object NotFound : Result
    data object NoCloneUrl : Result

    /** The configured instance was not accepted by the gate; credentials were never read. */
    data object NotConnected : Result

    /** [type] is the exception's type name only — messages can carry urls (A9). */
    data class Failed(val type: String) : Result
  }

  fun lookup(namespaceWithPath: String, acceptInstanceUrl: (String) -> Boolean): Result {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: return Result.NotConnected
    return try {
      val encoded = PathSegmentEncoder.encodeSegment(namespaceWithPath)
      val project = apiClient.fetchObject(
        "/projects/$encoded",
        type = GitLabProject::class.java,
        connection = connection,
      )
      val url = project.httpUrlToRepo
      if (url.isNullOrBlank()) Result.NoCloneUrl else Result.Ok(url, connection.instanceUrl)
    } catch (e: GitLabApiException) {
      if (e.statusCode == HTTP_NOT_FOUND) Result.NotFound else Result.Failed(e.javaClass.name)
    } catch (e: CancellationException) {
      throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
      Result.Failed(e.javaClass.name)
    }
  }
}
