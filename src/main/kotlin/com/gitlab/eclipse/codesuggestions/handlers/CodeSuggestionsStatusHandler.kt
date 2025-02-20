package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement

class CodeSuggestionsStatusHandler : AbstractHandler(), IElementUpdater {
  override fun execute(event: ExecutionEvent) = Unit

  override fun isEnabled() = false

  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>) {
    val service = service<CodeSuggestionsStateService>()

    val engagedCheck = service.getFirstEngagedCheck()
    if (engagedCheck == null) {
      element.setText("Code Suggestions: Enabled")
      element.setIcon(ThemeUtils.getThemedIcon("duoon_edit"))
    } else {
      element.setText("Code Suggestions: Disabled (${engagedCheck.checkId})")
      element.setIcon(ThemeUtils.getThemedIcon("duooff_edit"))
    }
  }
}
