package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService

fun refreshCodeSuggestionsLanguageToggle() {
  currentDisplay.syncExec {
    PlatformUI
      .getWorkbench()
      .getService(ICommandService::class.java)
      .refreshElements(
        "gitlab-eclipse-plugin.commands.toggleCodeSuggestionsForLanguage",
        emptyMap<Any, Any>()
      )
  }
}
