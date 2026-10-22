package com.gitlab.eclipse.currentbranch

/**
 * Pure editor-activation rule (status-bar design §4.3): the tracked repository is stale when the activated editor's
 * file lies outside the tracked work tree. Plain path-prefix logic on canonical strings with a separator boundary
 * (`/a/b` does not contain `/a/bc`); no file-system access, so it is safe on the UI thread.
 */
object RepositorySwitchRule {
  fun shouldRefresh(editorFile: String?, trackedWorkTree: String?): Boolean {
    if (editorFile == null) return false
    if (trackedWorkTree == null) return true
    val root = normalize(trackedWorkTree)
    val file = normalize(editorFile)
    return file != root && !file.startsWith("$root/")
  }

  private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')
}
