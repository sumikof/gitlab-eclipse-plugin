package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.isCodeSuggestionsApiAvailable
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.system.SystemUtils
import com.gitlab.eclipse.utils.theming.IconTone
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement

class CodeSuggestionsStatusHandler : AbstractHandler(), IElementUpdater {
  private val stateService by lazyService<CodeSuggestionsStateService>()

  override fun execute(event: ExecutionEvent) {
    val platform = service<PlatformUtils>()
    val activeTextEditor = platform.getActiveTextEditor()

    if (activeTextEditor != null) {
      currentDisplay.syncExec { platform.getTextWidget(activeTextEditor)?.setFocus() }
    } else {
      NotificationUtils.show("No active editor. Open a file to use Duo Code Suggestions.")
    }
  }

  override fun isEnabled() = stateService.isEnabled

  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>) {
    val engagedCheck = stateService.getFirstEngagedCheck()

    val iconTone = when {
      SystemUtils.isWindows() -> IconTone.DARK
      else -> null
    }

    when {
      // API is unavailable - this takes precedence over other states
      !isCodeSuggestionsApiAvailable -> {
        element.setText("Code Suggestions: Unavailable")
        element.setIcon(ThemeUtils.getThemedIcon("duo_off_edit", iconTone))
      }
      // No engaged checks means it's enabled
      engagedCheck == null -> {
        element.setText("Code Suggestions: Enabled")
        element.setIcon(ThemeUtils.getThemedIcon("duo_on_edit", iconTone))
      }
      // Otherwise, it's disabled for a specific reason
      else -> {
        element.setText("Code Suggestions: Disabled (${engagedCheck.checkId})")
        element.setIcon(ThemeUtils.getThemedIcon("duo_off_edit", iconTone))
      }
    }
  }
}
