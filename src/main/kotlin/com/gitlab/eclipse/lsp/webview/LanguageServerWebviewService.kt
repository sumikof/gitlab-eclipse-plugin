package com.gitlab.eclipse.lsp.webview

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

  fun sendThemeChange() {
    logger.info("Sending configuration change notification to Language Server.")
    coroutineScope.launch {
      try {
        languageServerWrapper.languageServer?.didChangeTheme(ThemeProvider.currentTheme())
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
