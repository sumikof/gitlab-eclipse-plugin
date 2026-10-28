package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.navigation.BROWSER_OPEN_FAILED_MESSAGE
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.NotificationUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

internal const val SELECT_SIDEBAR_ITEM_MESSAGE = "Select an item in the GitLab sidebar first."
internal const val NO_SIDEBAR_LINK_MESSAGE = "This item has no GitLab link."
internal const val BROWSER_FAILED_MESSAGE = BROWSER_OPEN_FAILED_MESSAGE

/**
 * The web URL of [node] for the sidebar's Open / Copy actions (design A D4/D8), or null after telling the user why
 * there is none: nothing selected, or no openable http(s) link. Visibility (`hasWebUrl`) hides both actions for such
 * nodes, so this only guards an invocation from outside the context menu.
 */
internal fun sidebarLinkOrNotify(node: SidebarNode?, notify: (String) -> Unit): String? {
  if (node == null) {
    notify(SELECT_SIDEBAR_ITEM_MESSAGE)
    return null
  }
  val url = sidebarWebUrl(node)
  if (url == null) notify(NO_SIDEBAR_LINK_MESSAGE)
  return url
}

/**
 * "Open in GitLab" on a sidebar node (NAV-05, VS Code `gl.openInGitLab` on tree items): opens the node's web URL in
 * the external browser. Runs on the UI thread, which [BrowserLauncher.openChecked] requires. Double-click
 * ([sidebarDoubleClick]) goes through the same gate with [BrowserLauncher.open], which notices a failure itself (D8a).
 */
@Suppress("unused")
class OpenSidebarItemInGitLabHandler(
  private val browser: BrowserLauncher = BrowserLauncher(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    run(selectedSidebarNode<SidebarNode>(event))
    return null
  }

  internal fun run(node: SidebarNode?) {
    val url = sidebarLinkOrNotify(node, notify) ?: return
    if (!browser.openChecked(url)) notify(BROWSER_FAILED_MESSAGE)
  }
}
