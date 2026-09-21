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
  ) : WebviewResolution {
    /**
     * Design §17, for the same reason as `WebviewEditorKey.toString`: a data class prints every
     * component, and [uri] is the advertised webview URI that §17 keeps out of the log. Nothing
     * stringifies a resolution today; this replaces the generated form rather than resting on that,
     * because one interpolation anywhere would be enough.
     */
    override fun toString(): String = "WebviewResolution.Resolved($id)"
  }

  data object LanguageServerUnavailable : WebviewResolution

  data class Failed(val cause: Throwable?) : WebviewResolution {
    /**
     * Design §17, and the second form of the exception-attachment leak the same section cites
     * (Phase 4 PR-4, Codex P1-1): the generated form calls `cause.toString()`, which is the class
     * name *and the message* — and the message is where a URI or a user's file path arrives. Only
     * the type survives here, which is what §17 permits of an exception.
     */
    override fun toString(): String = "WebviewResolution.Failed(type=${cause?.javaClass?.name})"
  }

  data class NotAdvertised(val id: String) : WebviewResolution

  data class NoUri(val id: String) : WebviewResolution
}

/** Design §7.1 / §7.1a. Acceptance criterion A6 (design §21). */
class WebviewUriResolver(
  private val wrapper: GitLabLanguageServerWrapper,
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
  /**
   * An address the client already knows for [id], consulted before the metadata request.
   *
   * Defaults to "there is none", so every existing caller resolves exactly as it did before. The
   * one webview that needs it — the Knowledge Graph — is never advertised in the metadata, so
   * without this seam it could only ever resolve to `NotAdvertised` (see [DirectWebview]).
   */
  private val directUris: (String) -> DirectWebview? = { null },
) {
  fun resolve(id: String): CompletableFuture<WebviewResolution> {
    // §7.1a
    val snapshot = wrapper.currentSnapshot
      ?: return CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)

    // Before the round trip, not after: a direct address needs no metadata, and asking for it
    // anyway would make the Knowledge Graph wait on a request whose answer cannot contain it.
    // The session still comes from the live snapshot, so supersession is detected as usual.
    val direct = try {
      directUris(id)
    } catch (e: Throwable) {
      return CompletableFuture.completedFuture(WebviewResolution.Failed(e))
    }
    if (direct != null) {
      return CompletableFuture.completedFuture(
        WebviewResolution.Resolved(id, direct.title, direct.uri, snapshot.session)
      )
    }

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
