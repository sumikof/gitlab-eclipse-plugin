package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.utils.NotificationUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * "Copy Link to Clipboard" on a sidebar node (NAV-05, VS Code `gl.copyLinkToClipboard` on tree items): copies the
 * node's web URL and says "copied" only once the write has landed ([ClipboardWriter.writeAndNotify]).
 */
@Suppress("unused")
class CopySidebarItemLinkHandler(
  private val clipboard: ClipboardWriter = ClipboardWriter(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    run(selectedSidebarNode<SidebarNode>(event))
    return null
  }

  internal fun run(node: SidebarNode?) {
    val url = sidebarLinkOrNotify(node, notify) ?: return
    clipboard.writeAndNotify(url, notify)
  }
}
