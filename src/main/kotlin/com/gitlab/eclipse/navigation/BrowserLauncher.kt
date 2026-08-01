package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.ui.PlatformUI
import java.net.URI

/** Opens a URL in the platform external browser. Marshals to the UI thread. */
class BrowserLauncher {
  private val logger by lazy { logger<BrowserLauncher>() }

  fun open(url: String) {
    currentDisplay.asyncExec {
      try {
        PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(URI.create(url).toURL())
      } catch (e: Exception) {
        logger.error("Failed to open URL: $url", e)
      }
    }
  }

  /**
   * Opens [url] in the external browser on the CURRENT (UI) thread, returning whether it launched.
   * Unlike [open] (which marshals via asyncExec and swallows the result), this runs synchronously so
   * the caller can react to failure. Caller MUST be on the UI thread (workbench browser support requires it).
   */
  fun openChecked(url: String): Boolean =
    try {
      PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(URI.create(url).toURL())
      true
    } catch (e: Exception) {
      logger.error("Failed to open URL: $url", e)
      false
    }
}
