package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.debug
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayout
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayoutStore
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformReadiness
import org.eclipse.swt.SWTError

/**
 * Handles webview-to-host messages from the Agent Platform webview (`root/duoAgentPlatform`).
 * AGENT-02 design §5.3.
 *
 * The language server forwards six notifications for this id (`appReady`, `insertCodeSnippet`,
 * `copyCodeSnippet`, `copyMessage`, `openMcpDashboard`, `switchToClassicLayout`; design §3.3). The
 * rest is the shared set the reference extension registers on the same id (design §3.4), kept for
 * parity although the pinned language server never sends it.
 *
 * [com.gitlab.eclipse.lsp.plugins.PluginRegistry] reads `declaredMethods` only, so every annotated
 * method is declared here and delegates to [handlers].
 *
 * The bus dispatches on a pool thread. Handlers that act on UI-confined state hop with [onUiThread]
 * (production: `asyncExec`, never `syncExec`, so the lsp4j reader is never blocked) and compare the
 * sender with the current session **after** the hop: a restart may land while the runnable waits
 * in the queue (design §5.3).
 *
 * Logs carry the message type, booleans and exception class names only — never a URL, snippet,
 * message text or exception message (design §9).
 *
 * @param handlers seam for the shared handler set; production builds it from the first three
 *   parameters, exactly as the other two chat controllers do.
 *
 * TooManyFunctions is suppressed: the eleven annotated handlers are the route set itself (the
 * registry cannot see inherited methods), and the two private helpers keep the session check in one
 * place instead of three copies.
 */
@Suppress("TooManyFunctions")
class DuoAgentPlatformWebViewController(
  platformUtils: PlatformUtils,
  currentFileContextProvider: CurrentFileContextProvider,
  insertCodeSnippetService: InsertCodeSnippetService,
  private val wrapper: GitLabLanguageServerWrapper,
  private val layoutStore: AgentPlatformLayoutStore,
  private val readiness: AgentPlatformReadiness,
  private val openMcpDashboard: () -> Unit,
  private val showDocument: (String) -> Unit,
  private val onUiThread: (Runnable) -> Unit,
  private val handlers: ChatWebViewMessageHandlers =
    ChatWebViewMessageHandlers(platformUtils, currentFileContextProvider, insertCodeSnippetService),
) : PluginController(AgentPlatformLayout.NEW_WEBVIEW_ID) {
  private val logger by lazy { logger<DuoAgentPlatformWebViewController>() }

  @PluginRequest("getCurrentFileContext")
  fun getCurrentFileContext(): FileContext? = handlers.getCurrentFileContext()

  @PluginNotification("appReady")
  fun appReady(session: LanguageServerSession) = onCurrentSession("appReady", session) {
    readiness.markReady(session)
  }

  /**
   * Insert and copy do not look at the session (design §5.3): each is the result of an explicit
   * user action, which stays valid even if the connection dropped just after the click.
   */
  @PluginNotification("insertCodeSnippet")
  fun insertCodeSnippet(notification: InsertCodeSnippetNotification) = handlers.insertCodeSnippet(notification)

  @PluginNotification("copyCodeSnippet")
  fun copyCodeSnippet(notification: CopyCodeSnippetNotification) = handlers.copyCodeSnippet(notification)

  @PluginNotification("copyMessage")
  fun copyMessage(notification: CopyMessageNotification) = handlers.copyMessage(notification)

  @PluginNotification("openMcpDashboard")
  fun openMcpDashboard(session: LanguageServerSession) = onCurrentSession("openMcpDashboard", session) {
    openMcpDashboard.invoke()
  }

  /** `hideSurvey` is accepted for the reference contract and read by nothing (design U-B). */
  @PluginNotification("switchToClassicLayout")
  fun switchToClassicLayout(
    @Suppress("UnusedParameter") notification: SwitchToClassicLayoutNotification,
    session: LanguageServerSession,
  ) = onCurrentSession("switchToClassicLayout", session) {
    layoutStore.set(AgentPlatformLayout.CLASSIC)
  }

  /**
   * Parity registration only. Unlike the shared handler, the text is never logged: it is webview
   * content (design §9). The type is webview-supplied too, so only a known category is logged as
   * itself; anything else is logged as `other`. No display UI is built because the pinned server
   * never sends it.
   */
  @PluginNotification("showMessage")
  fun showMessage(notification: ShowMessageNotification) {
    val category = notification.type.takeIf { it in SHOW_MESSAGE_TYPES } ?: "other"
    logger.info("Agent Platform showMessage received: type=$category")
  }

  /** Parity registration only; drives no state, like the agentic controller. */
  @PluginNotification("focusChange")
  fun focusChange(notification: FocusChangeNotification) {
    logger.debug("Agent Platform focus changed: isFocused=${notification.isFocused}")
  }

  /**
   * Goes through [showDocument] (production: `ShowDocumentLauncher`), which applies the external
   * URL policy and logs no URL — not the shared handler, which opens whatever it is given.
   */
  @PluginNotification("openLink")
  fun openLink(notification: OpenLinkNotification) = open(notification)

  @PluginNotification("openUrl")
  fun openUrl(notification: OpenLinkNotification) = open(notification)

  private fun open(notification: OpenLinkNotification) {
    (notification.href ?: notification.url)?.let(showDocument)
  }

  /**
   * Hops to the UI thread, then runs [action] only if [session] is still the current connection.
   * A failure is logged by class only: neither the hop's nor the action's message is safe to log.
   */
  private fun onCurrentSession(type: String, session: LanguageServerSession, action: () -> Unit) {
    try {
      onUiThread(Runnable { runIfCurrent(type, session, action) })
    } catch (e: Exception) {
      // asyncExec on a disposed display throws; that must not travel back up the dispatch thread.
      logger.warn("Agent Platform $type: could not reach the UI thread: ${e.javaClass.name}")
    }
  }

  private fun runIfCurrent(type: String, session: LanguageServerSession, action: () -> Unit) {
    if (session !== wrapper.currentSnapshot?.session) {
      logger.debug("Agent Platform: stale $type ignored")
      return
    }
    try {
      action()
    } catch (e: SWTError) {
      actionFailed(type, e)
    } catch (e: Exception) {
      actionFailed(type, e)
    }
  }

  /**
   * **`SWTError` extends `Error`, not `Exception`** (same reason as `ShowDocumentLauncher.openNow`):
   * this runs inside an `asyncExec` runnable, where an escaping error would reach the event loop,
   * which logs it with its message and stack trace and may raise the internal-error dialog.
   */
  private fun actionFailed(type: String, e: Throwable) {
    logger.error("Agent Platform $type failed: ${e.javaClass.name}")
  }

  private companion object {
    /** The categories the reference `showMessage` handler distinguishes. */
    val SHOW_MESSAGE_TYPES = setOf("info", "warning", "error")
  }
}
