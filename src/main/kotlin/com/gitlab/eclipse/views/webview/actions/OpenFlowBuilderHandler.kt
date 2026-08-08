package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.ActiveYamlEditorUri
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorOpener
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PlatformUI

/**
 * Opens the `root/flow` webview in the editor area, on the YAML file the active editor is showing.
 * Design §7.5.
 *
 * The active editor is read off the same page the tab is opened on, so the file the user is looking
 * at and the page the tab lands on cannot come from two different windows (design §8.1).
 */
@Suppress("unused")
class OpenFlowBuilderHandler : AbstractHandler() {
  private val logger = logger<OpenFlowBuilderHandler>()
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  override fun execute(event: ExecutionEvent): Any? {
    // Design §12: none of the three failures below is left silent.
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
    if (page == null) {
      logger.error("Cannot show webview '$WEBVIEW_ID': no active workbench page")
      NotificationUtils.show(FAILURE_MESSAGE)
      return null
    }

    when (val resolved = ActiveYamlEditorUri.of(page.activeEditor?.editorInput)) {
      is ActiveYamlEditorUri.Rejected -> {
        // Design §17: the category, which is fixed text, and never what was read off the editor.
        logger.error("Cannot show webview '$WEBVIEW_ID': ${resolved.category}")
        NotificationUtils.show(resolved.message)
      }

      is ActiveYamlEditorUri.Resolved -> open(page, resolved.fileUri)
    }
    return null
  }

  /** [fileUri] reaches the key and nothing else here; design §17 keeps it out of both channels. */
  private fun open(page: IWorkbenchPage, fileUri: String) {
    try {
      WebviewEditorOpener(languageServerWrapper).openOrReload(page, WebviewEditorInput.flowBuilder(fileUri))
    } catch (e: Exception) {
      // Design §17: the type only.
      logger.error("Cannot show webview '$WEBVIEW_ID': cannot open the editor, type=${e.javaClass.name}")
      NotificationUtils.show(FAILURE_MESSAGE)
    }
  }

  private companion object {
    const val WEBVIEW_ID = WebviewEditorInput.FLOW_WEBVIEW_ID
    const val FAILURE_MESSAGE = "Could not open the GitLab Flow Builder. See the Error Log."
  }
}
