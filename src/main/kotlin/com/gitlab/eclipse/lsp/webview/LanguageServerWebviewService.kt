package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.plugins.messages.ThemeProvider
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.e4.core.services.events.IEventBroker
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine
import org.eclipse.ui.PlatformUI

class LanguageServerWebviewService(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope
) {
  private val logger by lazy { logger<LanguageServerWebviewService>() }

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
    try {
      val events = PlatformUI.getWorkbench().getService(IEventBroker::class.java)
      events.subscribe(IThemeEngine.Events.THEME_CHANGED) {
        sendThemeChange()
      }
    } catch (ex: Exception) {
      logger.warn("Failed to subscribe to theme changes.", ex)
    }
  }
}
