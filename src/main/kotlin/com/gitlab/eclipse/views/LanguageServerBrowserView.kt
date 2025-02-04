@file:Suppress("MagicNumber", "UseOrEmpty")

package com.gitlab.eclipse.views

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.WebviewInfo
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.part.ViewPart
import java.util.*
import java.util.concurrent.TimeUnit

class LanguageServerBrowserView : ViewPart() {
  private val logger = logger<LanguageServerBrowserView>()

  private var browser: Browser? = null
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()
  private val duoChatStateService by lazyService<DuoChatStateService>()

  override fun createPartControl(parent: Composite?) {
    val osName = System.getProperty("os.name")
    val browserStyle = if (osName.contains("Windows", ignoreCase = true)) SWT.EDGE else SWT.WEBKIT

    browser = Browser(parent, browserStyle)
    setBrowserContent()
  }

  fun refresh() {
    setBrowserContent()
    browser?.redraw()
  }

  override fun setFocus() {
    browser?.setFocus()
  }

  override fun dispose() {
    super.dispose()
  }

  private fun setBrowserContent() {
    val duoChatEngagedCheck = duoChatStateService.getFirstEngagedCheck()
    when {
      duoChatEngagedCheck == null -> loadWebView()
      else -> loadUnauthenticatedWebview(duoChatEngagedCheck)
    }
  }

  private fun loadWebView() {
    val url = languageServerWrapper.languageServer
      ?.webviewMetadata()
      ?.completeOnTimeout(ArrayList<WebviewInfo?>(), 10L, TimeUnit.SECONDS)
      ?.join()
      ?.firstOrNull { it?.id == "duo-chat-v2" }
      ?.uris
      ?.firstOrNull()

    if (url == null) {
      logger.error("duo-chat-v2: no redirect available")
      return
    }

    browser?.setUrl(url)
  }

  private fun loadUnauthenticatedWebview(reason: FeatureStateChangeCheck) {
    browser?.setText(
      """
        <!doctype html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>GitLab</title>
        </head>
        <body>
            <p>GitLab Duo Chat is currently disabled: ${reason.details}</p>
        </body>
        </html>
      """.trimIndent()
    )
  }
}
