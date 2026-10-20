package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/** Opens the `root/mcp` webview in the editor area. Design §7.5. */
@Suppress("unused")
class ShowMcpDashboardHandler : AbstractHandler() {
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  /**
   * Runs on the thread the workbench dispatches commands on, which is the UI thread — what
   * [McpDashboardOpener] requires. **Nothing in this class or its tests asserts it**; it rests on
   * Eclipse's dispatch.
   */
  override fun execute(event: ExecutionEvent): Any? {
    McpDashboardOpener(languageServerWrapper).open()
    return null
  }
}
