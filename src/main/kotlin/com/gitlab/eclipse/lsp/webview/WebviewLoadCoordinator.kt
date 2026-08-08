package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CompletableFuture

/**
 * Decides what a webview surface should display, and nothing else. Design §7.2 / §8.1.
 *
 * [onUiThread] is how a decision gets back onto the thread that owns the surface — design §8.1 puts
 * `asyncExec` here, and this class stays free of SWT so that design §20's headless TDD applies.
 * Its own state is confined to that thread (design §15).
 */
class WebviewLoadCoordinator(
  private val resolver: WebviewUriResolver,
  private val wrapper: GitLabLanguageServerWrapper,
  private val onUiThread: (() -> Unit) -> Unit,
) {
  /** Design §7.2. */
  sealed interface Outcome {
    data class Show(
      val url: String,
      val title: String?,
      val session: LanguageServerSession,
    ) : Outcome

    data class Message(val text: String) : Outcome

    data object Superseded : Outcome

    data object SessionChanged : Outcome
  }

  private val logger = logger<WebviewLoadCoordinator>()

  /** Design §7.2a. Advanced by every [load]; only the newest load may produce a side effect. */
  private var generation = 0L

  fun load(id: String, queryParams: Map<String, String>): CompletableFuture<Outcome> {
    val myGeneration = ++generation
    val outcome = CompletableFuture<Outcome>()
    resolver.resolve(id).whenComplete { resolution, error ->
      onUiThread {
        // A surface that never hears back keeps its loading page forever, so every path out of
        // here has to complete `outcome` — including a resolver that broke its design §7.1
        // never-throws contract, and a decision that throws.
        outcome.complete(
          try {
            decide(id, myGeneration, resolution ?: WebviewResolution.Failed(error), queryParams)
          } catch (t: Throwable) {
            failure(id, CATEGORY_DECISION_FAILED, t, MESSAGE_UNREACHABLE)
          },
        )
      }
    }
    return outcome
  }

  private fun decide(
    id: String,
    myGeneration: Long,
    resolution: WebviewResolution,
    queryParams: Map<String, String>,
  ): Outcome {
    // §7.2a
    if (myGeneration != generation) return Outcome.Superseded

    return when (resolution) {
      is WebviewResolution.Resolved -> show(id, resolution, queryParams)
      WebviewResolution.LanguageServerUnavailable -> Outcome.Message(MESSAGE_WAITING)
      is WebviewResolution.Failed -> failure(id, CATEGORY_UNREACHABLE, resolution.cause, MESSAGE_UNREACHABLE)
      is WebviewResolution.NotAdvertised -> failure(id, CATEGORY_NOT_ADVERTISED, null, notProvidedMessage(id))
      is WebviewResolution.NoUri -> failure(id, CATEGORY_NO_URI, null, notProvidedMessage(id))
    }
  }

  private fun show(id: String, resolution: WebviewResolution.Resolved, queryParams: Map<String, String>): Outcome {
    // §7.1a: reference comparison against the session captured when the request started.
    if (resolution.session !== wrapper.currentSnapshot?.session) return Outcome.SessionChanged

    // §7.3a rule 3: a uri that cannot carry a query takes the failure path instead of being concatenated.
    val url = WebviewQueryBuilder.append(resolution.uri, queryParams)
      ?: return failure(id, CATEGORY_UNUSABLE_URI, null, MESSAGE_UNREACHABLE)

    return Outcome.Show(url, WebviewEditorTitles.titleFor(resolution), resolution.session)
  }

  /**
   * Design §12's Error Log entry. Design §17 limits it to the webview id and the category, plus the
   * *type* of [cause] — never the advertised uri, never the file path a caller put in `queryParams`,
   * and never [cause] itself, whose message can carry either.
   */
  private fun failure(id: String, category: String, cause: Throwable?, text: String): Outcome.Message {
    val type = cause?.let { " type=${it.javaClass.name}" }.orEmpty()
    logger.error("Cannot show webview '$id': $category$type")
    return Outcome.Message(text)
  }

  companion object {
    // Design §12's message-page column.
    internal const val MESSAGE_WAITING = "Waiting for the GitLab Language Server to start."
    internal const val MESSAGE_UNREACHABLE = "Cannot reach the GitLab Language Server."

    internal fun notProvidedMessage(id: String) = "This GitLab Language Server does not provide \"$id\"."

    // Design §12 keeps NoUri distinguishable from NotAdvertised in the log while both show the
    // same message page.
    private const val CATEGORY_UNREACHABLE = "metadata request failed"
    private const val CATEGORY_NOT_ADVERTISED = "not advertised"
    private const val CATEGORY_NO_URI = "advertised without a uri"
    private const val CATEGORY_UNUSABLE_URI = "advertised uri cannot carry a query"
    private const val CATEGORY_DECISION_FAILED = "decision failed"
  }
}
