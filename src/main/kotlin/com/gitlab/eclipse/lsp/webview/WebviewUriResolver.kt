package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.knowledgegraph.KnowledgeGraphState
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
   * Webviews the client can address itself (plan §16 / §16.1), asked before the metadata request
   * with the id and the session of the snapshot this resolution reads — the one place "current
   * session" comes from. The default knows none, so every caller that does not pass one resolves
   * exactly as before. Production passes `KnowledgeGraphState.directWebviewFor` via [forProduction].
   */
  private val directUris: (String, LanguageServerSession) -> DirectWebview? = { _, _ -> null },
) {
  fun resolve(id: String): CompletableFuture<WebviewResolution> {
    // §7.1a
    val snapshot = wrapper.currentSnapshot
      ?: return CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)

    // Plan §16: a direct address is never in the metadata response, so it is asked for first. A6: a
    // throwing seam becomes Failed rather than escaping resolve.
    val direct = try {
      directUris(id, snapshot.session)
    } catch (e: Throwable) {
      return CompletableFuture.completedFuture(WebviewResolution.Failed(e))
    }
    if (direct != null) {
      return CompletableFuture.completedFuture(
        WebviewResolution.Resolved(id, direct.title, direct.uri, snapshot.session),
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

  companion object {
    // §7.1a: deliberately duplicates LanguageServerBrowserView.METADATA_TIMEOUT_SECONDS's value,
    // not its declaration.
    private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
  }
}

/**
 * The resolver every production surface builds (plan §16.1): the Knowledge Graph resolves through
 * the address `KnowledgeGraphState` holds for the snapshot's session, every other id through
 * metadata. Constructing [WebviewUriResolver] directly leaves the graph permanently `NotAdvertised`.
 */
fun WebviewUriResolver.Companion.forProduction(wrapper: GitLabLanguageServerWrapper): WebviewUriResolver =
  WebviewUriResolver(wrapper, directUris = KnowledgeGraphState::directWebviewFor)
