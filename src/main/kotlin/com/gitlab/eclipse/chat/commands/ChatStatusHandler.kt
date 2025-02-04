package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.inject.service
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement
import org.eclipse.ui.plugin.AbstractUIPlugin

class ChatStatusHandler : AbstractHandler(), IElementUpdater {
  override fun execute(event: ExecutionEvent) = Unit

  override fun isEnabled() = false

  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>) {
    val service = service<DuoChatStateService>()

    val engagedCheck = service.getFirstEngagedCheck()
    if (engagedCheck == null) {
      element.setText("Duo Chat: Enabled")
      element.setIcon(
        AbstractUIPlugin.imageDescriptorFromPlugin(
          "com.gitlab.eclipse.gitlab-eclipse-plugin",
          "icons/duo-chat.png"
        )
      )
    } else {
      element.setText("Duo Chat: Disabled (${engagedCheck.checkId})")
      element.setIcon(
        AbstractUIPlugin.imageDescriptorFromPlugin(
          "com.gitlab.eclipse.gitlab-eclipse-plugin",
          "icons/duo-chat-off.png"
        )
      )
    }
  }
}
