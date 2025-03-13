package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.system.SystemUtils
import com.gitlab.eclipse.utils.theming.IconTone
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement

class ChatStatusHandler : AbstractHandler(), IElementUpdater {
  private val stateService by lazyService<DuoChatStateService>()

  override fun execute(event: ExecutionEvent) {
    openDuoChatWindow()
  }

  override fun isEnabled() = stateService.isEnabled

  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>) {
    val engagedCheck = stateService.getFirstEngagedCheck()

    val iconTone = when {
      SystemUtils.isWindows() -> IconTone.DARK
      else -> null
    }

    if (engagedCheck == null) {
      element.setText("Duo Chat: Enabled")
      element.setIcon(ThemeUtils.getThemedIcon("chat_on_obj", iconTone))
    } else {
      element.setText("Duo Chat: Disabled (${engagedCheck.checkId})")
      element.setIcon(ThemeUtils.getThemedIcon("chat_off_obj", iconTone))
    }
  }
}
