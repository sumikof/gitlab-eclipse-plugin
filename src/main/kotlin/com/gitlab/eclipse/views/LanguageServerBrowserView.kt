@file:Suppress("MagicNumber", "UseOrEmpty")

package com.gitlab.eclipse.views

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.WebviewInfo
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_HTTP_URL
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.part.ViewPart
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

class LanguageServerBrowserView : ViewPart() {
  private val logger = logger<LanguageServerBrowserView>()

  private var browser: Browser? = null
  private val preferenceStore by lazyService<ScopedPreferenceStore>()
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  override fun createPartControl(parent: Composite?) {
    val osName = System.getProperty("os.name")
    val browserStyle = if (osName.contains("Windows", ignoreCase = true)) SWT.EDGE else SWT.WEBKIT

    browser = Browser(parent, browserStyle)
    browser?.setText(webviewContent())
  }

  override fun setFocus() {
    browser?.setFocus()
  }

  override fun dispose() {
    super.dispose()
  }

  private fun webviewContent(): String {
    var js: String? = null
    try {
      javaClass.getResourceAsStream("/webviews/javascript/LanguageServerBrowserView.js")?.use { inputStream ->
        js = String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
      }
    } catch (e: IOException) {
      logger.error(e.message, e)
    }
    val buffer = StringBuilder()

    buffer.append("<!doctype html>")
    buffer.append("<html lang=\"en\">")
    buffer.append("<head>")
    buffer.append("<meta charset=\"utf-8\">")
    buffer.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    buffer.append("<title>GitLab</title>")
    buffer.append("<script>$js</script>")
    buffer.append("</head>")

    val webviews = ArrayList<WebviewInfo?>()

    // TODO: Trigger a browser event instead of synchronously handling this event?
    if (languageServerWrapper.languageServer != null) {
      webviews.addAll(
        languageServerWrapper.languageServer
          ?.webviewMetadata()
          ?.completeOnTimeout(ArrayList<WebviewInfo?>(), 10L, TimeUnit.SECONDS)
          ?.join()
          ?: emptyList()
      )

      logger.warn("webview: $webviews")
      val lspUrl = preferenceStore.getString(LANGUAGE_SERVER_HTTP_URL)
      val redirect = webviews.stream()
        .filter { it?.id == "duo-chat-v2" }
        .findFirst()
        .map { it?.uris?.get(0) ?: lspUrl }
        .orElse(lspUrl)

      if (redirect != null) {
        buffer.append("<meta http-equiv=\"Refresh\" content=\"0; url='$redirect'\" />")
      } else {
        buffer.append("<meta http-equiv=\"Refresh\" content=\"0; url='$lspUrl'\" />")
      }
      logger.warn("webview: ${redirect ?: "no redirect"}")
    } else {
      logger.warn("webview: no redirect available")
    }

    buffer.append("<body>")
    buffer.append("<p>Hello world from the GitLab for Eclipse.</p>")
    buffer.append(
      "<p>Webview content loaded from development environment at: " + DateFormat.getInstance().format(Date.from(Instant.now())) + "</p>"
    )
    buffer.append("</body>")
    buffer.append("</html>")
    return buffer.toString()
  }
}
