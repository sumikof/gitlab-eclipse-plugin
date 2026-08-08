package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.WebviewInfo
import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Design §7.1 / §7.1a. */
sealed interface WebviewResolution {
  data class Resolved(
    val id: String,
    val title: String,
    val uri: String,
    val session: LanguageServerSession,
  ) : WebviewResolution

  data object LanguageServerUnavailable : WebviewResolution

  data class Failed(val cause: Throwable?) : WebviewResolution

  data class NotAdvertised(val id: String) : WebviewResolution

  data class NoUri(val id: String) : WebviewResolution
}

/** Design §7.1 / §7.1a. Acceptance criterion A6 (design §21). */
class WebviewUriResolver(
  private val wrapper: GitLabLanguageServerWrapper,
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
  private val logger by lazy { logger<WebviewUriResolver>() }

  fun resolve(id: String): CompletableFuture<WebviewResolution> {
    // §7.1a: read currentSnapshot exactly once — proxy and session must come from the same
    // read, or a restart between two reads could pair a new proxy with the old session.
    val snapshot = wrapper.currentSnapshot
      ?: return CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)

    val metadataFuture = try {
      snapshot.proxy.webviewMetadata()
    } catch (e: Throwable) {
      logFailure(id, e)
      return CompletableFuture.completedFuture(WebviewResolution.Failed(e))
    } ?: return CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)

    val result = CompletableFuture<WebviewResolution>()
    metadataFuture
      .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
      .whenComplete { metadata, error ->
        result.complete(
          if (error != null) {
            logFailure(id, error)
            WebviewResolution.Failed(error)
          } else {
            resolveFromMetadata(id, metadata, snapshot.session)
          },
        )
      }
    return result
  }

  private fun resolveFromMetadata(
    id: String,
    metadata: List<WebviewInfo?>?,
    session: LanguageServerSession,
  ): WebviewResolution {
    val info = metadata.orEmpty().filterNotNull().firstOrNull { it.id == id }
      ?: return WebviewResolution.NotAdvertised(id)
    // uris.firstOrNull(): matches the reference implementation's (acknowledged incomplete)
    // choice, not independently re-solved here — design §7.1.
    val uri = info.uris.firstOrNull() ?: return WebviewResolution.NoUri(id)
    return WebviewResolution.Resolved(info.id, info.title, uri, session)
  }

  // Design §17: never log the resolved URI or a user file path — only the webview id and the
  // failure's type.
  private fun logFailure(id: String, cause: Throwable) {
    logger.warn("Failed to resolve webview metadata for id=$id (exceptionType=${cause.javaClass.simpleName})")
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MILLIS = 10_000L
  }
}
