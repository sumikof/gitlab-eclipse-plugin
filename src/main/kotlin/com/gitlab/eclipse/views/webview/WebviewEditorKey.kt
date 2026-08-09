package com.gitlab.eclipse.views.webview

/**
 * What makes two webview editor tabs the same tab. Design §7.3.
 *
 * [queryParams] is part of the identity, so a match means the same webview *and* the same content.
 * Design §7.3 records what happened when it was left out.
 */
data class WebviewEditorKey(val webviewId: String, val queryParams: Map<String, String>) {
  /**
   * Design §17. The generated `toString()` of a data class prints every component, and for
   * `root/flow` [queryParams] holds the path of the user's file. Nothing stringifies a key today;
   * this replaces the generated form rather than resting on that, because one interpolation
   * anywhere would be enough.
   */
  override fun toString(): String = "WebviewEditorKey($webviewId)"
}
