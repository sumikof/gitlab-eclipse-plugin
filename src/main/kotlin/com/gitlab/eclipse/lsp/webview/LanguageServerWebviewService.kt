package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.e4.core.services.events.IEventBroker
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine
import org.eclipse.ui.PlatformUI
import java.util.concurrent.atomic.AtomicBoolean

class LanguageServerWebviewService(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope
) {
  private val logger by lazy { logger<LanguageServerWebviewService>() }

  private val themeSubscribed = AtomicBoolean(false)

  // Overload (not a default argument): a default expression reading the wrapper would be
  // evaluated by the Kotlin $default bridge even on MockK mocks, NPE-ing tests that mock
  // this service and trigger a no-arg send. The theme-event subscription deliberately
  // uses this no-arg path: one subscription serves all restarts by resolving the
  // wrapper's current server when the event fires.
  fun sendThemeChange() = sendThemeChange(languageServerWrapper.languageServer)

  // The [server] parameter binds the queued didChangeTheme to the server captured at
  // CALL time (the readiness callback passes its own initialized proxy), so a rapid
  // restart strands the queued send with the old server instead of redirecting it at a
  // new pre-initialize one.
  fun sendThemeChange(server: GitLabLanguageServer?) {
    logger.info("Sending configuration change notification to Language Server.")
    coroutineScope.launch {
      try {
        server?.didChangeTheme(ThemeProvider.currentTheme())
      } catch (e: Throwable) {
        logger.error("Failed to send update language server theme. ", e)
      }
    }
  }

  fun subscribeToThemeChanges() {
    // Subscribe once for the lifetime of this service: every language-server (re)start
    // reaches this call, and the broker never gets unsubscribed, so re-subscribing would
    // accumulate one duplicate didChangeTheme per restart. A single subscription is
    // enough because the handler resolves the wrapper's CURRENT language server at
    // event time.
    if (!themeSubscribed.compareAndSet(false, true)) {
      return
    }
    try {
      val events = PlatformUI.getWorkbench().getService(IEventBroker::class.java)
      events.subscribe(IThemeEngine.Events.THEME_CHANGED) {
        sendThemeChange()
      }
    } catch (ex: Exception) {
      // Allow the next start()/restart() to retry the subscription.
      themeSubscribed.set(false)
      logger.warn("Failed to subscribe to theme changes.", ex)
    }
  }
}
