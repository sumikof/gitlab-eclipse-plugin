package com.gitlab.eclipse.lsp.webview

/** Design §7.3. */
object WebviewEditorTitles {
  /** The tab name a resolution justifies, or null to leave the tab name as it is. Design §7.3. */
  fun titleFor(resolution: WebviewResolution): String? =
    (resolution as? WebviewResolution.Resolved)?.title
}
