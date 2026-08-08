package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.WebviewInfo
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
  fun resolve(id: String): CompletableFuture<WebviewResolution> {
    // §7.1a
    val snapshot = wrapper.currentSnapshot
      ?: return CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)

    val metadataFuture = try {
      snapshot.proxy.webviewMetadata()
    } catch (e: Throwable) {
      return CompletableFuture.completedFuture(WebviewResolution.Failed(e))
    } ?: return CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)

    val result = CompletableFuture<WebviewResolution>()
    metadataFuture
      .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
      .whenComplete { metadata, error ->
        // A6: this lambda's own body must never leave `result` uncompleted. `metadata` here can
        // come straight from lsp4j's Gson deserialization, which bypasses the constructor and its
        // non-null checks — resolveFromMetadata can throw a real NPE, not just a hypothetical one.
        val resolution = try {
          if (error != null) {
            WebviewResolution.Failed(error)
          } else {
            resolveFromMetadata(id, metadata, snapshot.session)
          }
        } catch (t: Throwable) {
          WebviewResolution.Failed(t)
        }
        result.complete(resolution)
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
    // §7.1
    val uri = info.uris.firstOrNull() ?: return WebviewResolution.NoUri(id)
    return WebviewResolution.Resolved(info.id, info.title, uri, session)
  }

  private companion object {
    // §7.1a: deliberately duplicates LanguageServerBrowserView.METADATA_TIMEOUT_SECONDS's value,
    // not its declaration.
    const val DEFAULT_TIMEOUT_MILLIS = 10_000L
  }
}
