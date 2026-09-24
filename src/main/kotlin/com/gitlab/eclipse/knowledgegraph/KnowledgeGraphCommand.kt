package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.knowledgegraph.KnowledgeGraphState.WEBVIEW_ID
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginRequest
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * What the "Show GitLab Knowledge Graph" command does (plan §9.3 steps 2–6), apart from the workbench.
 *
 * The tab itself decides between the graph and [KnowledgeGraphState.NOT_RUNNING_MESSAGE]: it asks
 * [KnowledgeGraphState] for the current connection when it resolves. All this class adds is the
 * compensation for a `ready` that fired before the client was listening (plan §9.3 "★★ R7"): when no
 * address is held for the current connection, it asks the server with `getUrl` — once, when the user
 * runs the command, never on a timer — and opens the tab only after the answer, so the tab sees it.
 *
 * Starts on the UI thread and never waits there. The answer is handled on whatever thread completes
 * the future; the tab is then opened back on the UI thread through [onUiThread].
 *
 * @property currentSnapshot the wrapper's current connection; read once when the command runs and
 *   once more when the answer arrives, so an answer from a connection that has since been replaced is
 *   not recorded (A14 / A25)
 * @property onUiThread hops to the UI thread without waiting; `asyncExec` in production
 * @property timeoutMillis how long `getUrl` may take; no retry after it
 */
class KnowledgeGraphCommand(
  private val currentSnapshot: () -> LanguageServerHandle?,
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
  private val log by lazy { logger<KnowledgeGraphCommand>() }

  /**
   * Opens the Knowledge Graph tab with [openTab], after asking the server for the address if none is
   * held. Called on the UI thread; [openTab] is always run on it too, exactly once. Throws nothing
   * that [openTab] does not.
   */
  fun run(openTab: () -> Unit) {
    val handle = currentSnapshot()
    // Ruling R2: with no connection the tab shows its own "waiting for the language server" page.
    // A26: an address the current connection reported is shown without asking again.
    if (handle == null || KnowledgeGraphState.urlFor(handle.session) != null) {
      openTab()
      return
    }

    val answer = request(handle)
    if (answer == null) {
      openTab()
      return
    }
    answer
      .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
      .whenComplete { response, error -> onAnswer(handle, response, error, openTab) }
  }

  /**
   * Sends `getUrl` on the proxy captured with [handle] — never a re-read of the wrapper, which could be
   * a newer connection. `null` when the proxy throws or answers with no future: both mean no answer.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun request(handle: LanguageServerHandle): CompletableFuture<Any?>? = try {
    // Declared non-null, but a Java proxy can still hand back null.
    val future: CompletableFuture<Any?>? = handle.proxy.pluginRequest(GET_URL)
    future
  } catch (e: Exception) {
    logQuietly("Could not ask for the Knowledge Graph address, type=${e.javaClass.name}")
    null
  }

  /**
   * Runs on whichever thread completed the answer. Records the address only if [handle]'s connection
   * is still the current one, then opens the tab on the UI thread whatever the answer was: without an
   * address the tab shows [KnowledgeGraphState.NOT_RUNNING_MESSAGE] (A8). Nothing escapes.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun onAnswer(handle: LanguageServerHandle, response: Any?, error: Throwable?, openTab: () -> Unit) {
    try {
      if (error != null) {
        // A timeout or an error: the tab shows the message page. The type only (§15).
        logQuietly("Could not get the Knowledge Graph address, type=${error.javaClass.name}")
      } else {
        // An empty address is how the server says gkg is not running; that is not logged (§15).
        KnowledgeGraphState.record(knowledgeGraphUrlOf(response), handle.session, currentSnapshot()?.session)
      }
    } catch (e: Exception) {
      logQuietly("Could not record the Knowledge Graph address, type=${e.javaClass.name}")
    }

    try {
      onUiThread(Runnable { openTab() })
    } catch (e: Throwable) {
      // The display is gone (the workbench is closing): there is nowhere to open the tab.
      logQuietly("Could not open webview '$WEBVIEW_ID' on the UI thread, type=${e.javaClass.name}")
    }
  }

  /** The platform log can be gone while the workbench stops; losing the line must not escape. */
  private fun logQuietly(message: String) {
    runCatching { log.warn(message) }
  }

  private companion object {
    /** Plan §3: `KNOWLEDGE_GRAPH_WEBVIEW_ID`, the plugin's id, and its `getUrl` request handler. */
    val GET_URL = ExtensionToPluginRequest(WEBVIEW_ID, "getUrl")

    // Plan §13: the value of WebviewUriResolver's default timeout, not its (private) declaration.
    const val DEFAULT_TIMEOUT_MILLIS = 10_000L
  }
}
