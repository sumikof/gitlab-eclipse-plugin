package com.gitlab.eclipse.handlers

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.action.MenuManager
import org.eclipse.swt.widgets.Event
import org.eclipse.swt.widgets.Menu
import org.eclipse.swt.widgets.ToolItem
import org.eclipse.ui.PlatformUI

class ShowPluginStatusMenu : AbstractHandler() {
  var menu: Menu? = null

  override fun execute(event: ExecutionEvent) {
    if (menu == null) {
      val item = event.trigger
        .let { it as? Event }
        ?.let { it.widget as? ToolItem }
        ?: return

      val menuService = PlatformUI.getWorkbench().getService(org.eclipse.ui.menus.IMenuService::class.java)
      val menuManager = MenuManager("Show GitLab Status", "gitlab-eclipse-plugin.menus.statusWidgetMenu")
      menuService.populateContributionManager(menuManager, "menu:gitlab-eclipse-plugin.menus.statusWidgetMenu")

      menu = menuManager.createContextMenu(item.parent)
    }

    menu?.visible = true
  }

  override fun isEnabled() = true
}
