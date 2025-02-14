package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.utils.ThemeUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement

class OpenDuoChatCommandHandler : AbstractHandler(), IElementUpdater {
  override fun execute(event: ExecutionEvent) {
    openDuoChatWindow()
  }

  override fun updateElement(element: UIElement, parameters: Map<*, *>) {
    element.setIcon(ThemeUtils.getThemedIcon("chaton_obj"))
  }
}
