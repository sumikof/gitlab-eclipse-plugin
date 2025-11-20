package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService

class CodeSuggestionsStateService {
  private var checks: List<FeatureStateChangeCheck>? = null
  val isEnabled: Boolean
    get() = checks?.none { it.engaged } ?: false

  fun update(featureStateChange: FeatureStateChange) {
    val previousState = isEnabled
    checks = featureStateChange.allChecks

    if (previousState != isEnabled) {
      currentDisplay.asyncExec { refreshCodeSuggestionsStatus() }
    }
  }

  fun getFirstEngagedCheck() = checks?.firstOrNull { it.engaged }

  private fun refreshCodeSuggestionsStatus() {
    PlatformUI
      .getWorkbench()
      .getService(ICommandService::class.java)
      .refreshElements("gitlab-eclipse-plugin.commands.codeSuggestionsStatus", emptyMap<Any, Any>())
  }
}
