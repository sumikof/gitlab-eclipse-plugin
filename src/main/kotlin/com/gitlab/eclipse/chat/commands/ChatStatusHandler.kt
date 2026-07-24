package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.ChatAvailabilityService
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
  companion object {
    private const val CLASSIC_WEBVIEW_ID = "duo-chat-v2"
  }

  private val availabilityService by lazyService<ChatAvailabilityService>()

  override fun execute(event: ExecutionEvent) {
    openDuoChatWindow()
  }

  override fun isEnabled() = availabilityService.anyChatEnabled

  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>) {
    val iconTone = when {
      SystemUtils.isWindows() -> IconTone.DARK
      else -> null
    }

    if (availabilityService.anyChatEnabled) {
      element.setText("Duo Chat: Enabled")
      element.setIcon(ThemeUtils.getThemedIcon("chat_on_obj", iconTone))
    } else {
      // The status element has no notion of the selected webview, so the classic webview's
      // disabled reason stands in for all of Duo Chat: when everything is disabled, both
      // webviews are gated by the same server-side checks, and classic is the default surface.
      val reason = availabilityService.availabilityFor(CLASSIC_WEBVIEW_ID).disabledReason
      val details = if (reason != null) " ($reason)" else ""
      element.setText("Duo Chat: Disabled$details")
      element.setIcon(ThemeUtils.getThemedIcon("chat_off_obj", iconTone))
    }
  }
}
