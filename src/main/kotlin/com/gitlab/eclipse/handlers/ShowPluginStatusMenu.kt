package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.codesuggestions.status.DuoStatusInputsProvider
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.action.MenuManager
import org.eclipse.swt.SWTException
import org.eclipse.swt.widgets.Event
import org.eclipse.swt.widgets.Menu
import org.eclipse.swt.widgets.ToolBar
import org.eclipse.swt.widgets.ToolItem
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.menus.IMenuService
import org.eclipse.ui.menus.UIElement

/**
 * The GitLab Duo trim item: its icon and tooltip follow the Duo status of the item's own window
 * (design §8.3), and a click opens the status pulldown menu of that window.
 */
class ShowPluginStatusMenu : AbstractHandler(), IElementUpdater {
  private class StatusMenu(val menu: Menu, val manager: MenuManager, val menuService: IMenuService)

  /** One menu per trim tool bar, hence per shell: a second window never opens the first one's menu. */
  private val menus = OwnerScopedCache<ToolBar, StatusMenu>(
    isAlive = { !it.menu.isDisposed },
    release = { status ->
      status.menuService.releaseContributions(status.manager)
      status.manager.dispose()
    },
  )

  override fun execute(event: ExecutionEvent): Any? {
    val item = (event.trigger as? Event)?.widget as? ToolItem ?: return null
    val toolBar = item.parent ?: return null
    menus.getOrCreate(toolBar) { createMenu(event, toolBar) }?.menu?.visible = true
    return null
  }

  override fun isEnabled() = true

  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>?) {
    try {
      val window = element.serviceLocator?.getService(IWorkbenchWindow::class.java)
      val status = service<DuoStatusInputsProvider>().status(window)
      element.setIcon(ThemeUtils.getThemedIcon(status.icon.iconName))
      element.setTooltip(status.tooltip)
    } catch (ignored: SWTException) {
      /* display disposed mid-render: nothing to update */
    } catch (ignored: IllegalStateException) {
      /* workbench closing: nothing to update */
    } catch (e: RuntimeException) {
      // Keep the current icon and tooltip; never break the trim render.
      logger<ShowPluginStatusMenu>().warn("Duo status trim update failed (${e.javaClass.simpleName}).")
    }
  }

  /**
   * Built with the menu service of the trigger's own window (R2-F2), so the menu items resolve the
   * same window as the trim item. Released with its tool bar (shell dispose or trim rebuild, I5).
   */
  private fun createMenu(event: ExecutionEvent, toolBar: ToolBar): StatusMenu? {
    val shell = toolBar.shell
    val window = PlatformUI.getWorkbench().workbenchWindows.firstOrNull { it.shell === shell }
      ?: HandlerUtil.getActiveWorkbenchWindow(event)
      ?: return null
    val menuService = window.getService(IMenuService::class.java) ?: return null

    val manager = MenuManager("Show GitLab Status", STATUS_MENU_ID)
    menuService.populateContributionManager(manager, "menu:$STATUS_MENU_ID")
    val status = StatusMenu(manager.createContextMenu(toolBar), manager, menuService)
    toolBar.addDisposeListener { menus.remove(toolBar) }
    return status
  }

  private companion object {
    const val STATUS_MENU_ID = "gitlab-eclipse-plugin.menus.statusWidgetMenu"
  }
}
