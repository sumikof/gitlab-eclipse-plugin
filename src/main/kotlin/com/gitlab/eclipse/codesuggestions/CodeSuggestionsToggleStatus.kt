package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService

fun refreshCodeSuggestionsToggle() {
  currentDisplay.syncExec {
    PlatformUI
      .getWorkbench()
      .getService(ICommandService::class.java)
      .refreshElements(
        "gitlab-eclipse-plugin.commands.toggleCodeSuggestions",
        emptyMap<Any, Any>()
      )
  }
}
