package com.gitlab.eclipse.security.actions

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticUri
import com.gitlab.eclipse.security.details.SecurityVulnDetailsClient
import com.gitlab.eclipse.security.scanUriOf
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorOpener
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.texteditor.ITextEditor

/** Shown when there is no file in a text editor, or no cursor line in it, to show the details of. */
internal const val NO_TEXT_EDITOR_MESSAGE = "Open a file in a text editor to show GitLab vulnerability details."

/** Shown when there is no connection to send the finding over; no tab is opened (A18). */
internal const val LANGUAGE_SERVER_NOT_RUNNING_MESSAGE = "The GitLab Language Server is not running."

/** What the command does with the editor the user is in. */
internal sealed interface VulnDetailsDecision {
  /** Tell the user [message] and do nothing else. */
  data class Notify(val message: String) : VulnDetailsDecision

  /** Show the finding on [line] (1-based) of [path] from the findings retained for [handle]'s connection. */
  data class Show(val handle: LanguageServerHandle, val path: String, val line: Int) : VulnDetailsDecision
}

/**
 * Decides what the command does, from the active [editor] and the connection [handle] the caller read.
 *
 * [path] is the key the intake stores findings under: the editor input's `scanUriOf` passed through
 * `DiagnosticUri.normalize`, exactly as `SecurityScanLauncher.launch` keys the scan it counts. A file that
 * is not in a text editor, is not on the local file system, or has no cursor line gets the same notice —
 * the user's remedy is the same. The editor is judged before the connection.
 */
internal fun decideVulnDetails(editor: IEditorPart?, handle: LanguageServerHandle?): VulnDetailsDecision {
  val textEditor = editor?.let { it.getAdapter(ITextEditor::class.java) ?: (it as? ITextEditor) }
  val path = textEditor?.editorInput?.let { scanUriOf(it) }?.let { DiagnosticUri.normalize(it) }
  val startLine = (textEditor?.selectionProvider?.selection as? ITextSelection)?.startLine
  if (path == null || startLine == null || startLine < 0) return VulnDetailsDecision.Notify(NO_TEXT_EDITOR_MESSAGE)
  if (handle == null) return VulnDetailsDecision.Notify(LANGUAGE_SERVER_NOT_RUNNING_MESSAGE)
  return VulnDetailsDecision.Show(handle, path, startLine + 1)
}

/**
 * `com.gitlab.eclipse.commands.ShowVulnerabilityDetails` — shows the scan finding on the cursor line in the
 * `security-vuln-details` webview (design §9.2 steps 1–3).
 *
 * A thin shell over [decideVulnDetails] and [SecurityVulnDetailsClient.show]: every other gate and message
 * lives in the client, which returns at once and does its work off the UI thread.
 */
@Suppress("unused")
class ShowVulnDetailsHandler : AbstractHandler() {
  /**
   * Runs on the thread the workbench dispatches commands on, the UI thread, which reading the active editor
   * and page requires. The wrapper's snapshot is read **once**: the client stays bound to that connection.
   */
  override fun execute(event: ExecutionEvent): Any? {
    // Captured now: the tab opens on the page the user ran the command in. Without a page there is no
    // active editor either, so the notice is the same.
    val page = HandlerUtil.getActiveWorkbenchWindow(event)?.activePage
    if (page == null) {
      NotificationUtils.show(NO_TEXT_EDITOR_MESSAGE)
      return null
    }
    val wrapper = service<GitLabLanguageServerWrapper>()
    when (val decision = decideVulnDetails(HandlerUtil.getActiveEditor(event), wrapper.currentSnapshot)) {
      is VulnDetailsDecision.Notify -> NotificationUtils.show(decision.message)
      is VulnDetailsDecision.Show -> {
        // The client runs this on the UI thread, after the send, and reports anything it throws.
        val openTab = { WebviewEditorOpener(wrapper).openOrReload(page, WebviewEditorInput.securityVulnDetails()) }
        service<SecurityVulnDetailsClient>().show(decision.handle, decision.path, decision.line, openTab)
      }
    }
    return null
  }
}
