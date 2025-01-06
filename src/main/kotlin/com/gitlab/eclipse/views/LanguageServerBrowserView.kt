package com.gitlab.eclipse.views

import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider
import com.gitlab.eclipse.lsp.WebviewInfo
import org.eclipse.core.runtime.Platform
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.part.ViewPart
import org.osgi.framework.FrameworkUtil
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

class LanguageServerBrowserView : ViewPart() {
    private var browser: Browser? = null

    override fun createPartControl(parent: Composite?) {
        browser = Browser(parent, SWT.WEBKIT)
        browser!!.setText(webviewContent())
    }

    override fun setFocus() {
        browser!!.setFocus()
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
            Platform.getLog(javaClass).error(e.message, e)
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
        if (GitLabLanguageServerProvider.languageServer != null) {
            webviews.addAll(
                GitLabLanguageServerProvider.languageServer
                    ?.webviewMetadata()
                    ?.completeOnTimeout(ArrayList<WebviewInfo?>(), 10L, TimeUnit.SECONDS)
                    ?.join()
                    ?: emptyList()
            )

            Platform.getLog(FrameworkUtil.getBundle(GitLabLanguageServerProvider::class.java)).warn("webview: $webviews")
            val redirect = webviews.stream()
                .filter { w: WebviewInfo? -> "duo-chat" == w!!.id }
                .findFirst()
                .map { it!!.uris[0] }
                .orElse(null)
            if (redirect != null) {
                buffer.append("<meta http-equiv=\"Refresh\" content=\"0; url='$redirect'\" />")
            }
            Platform.getLog(FrameworkUtil.getBundle(GitLabLanguageServerProvider::class.java)).warn("webview: ${redirect ?: "no redirect"}")
        } else {
            Platform.getLog(FrameworkUtil.getBundle(GitLabLanguageServerProvider::class.java)).warn("webview: no redirect available")
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

    companion object {
        /**
         * The ID of the view as specified by the extension.
         */
        const val ID: String = "com.gitlab.eclipse.views.LanguageServerBrowserView"
    }
}