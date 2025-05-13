package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.ui.contexts.IContextActivation
import org.eclipse.ui.contexts.IContextService

class CodeSuggestionsCommandContext(
  private val platformUtils: PlatformUtils
) {
  private val contextService by lazy { platformUtils.getWorkbench().getService(IContextService::class.java) }
  private var contextActivation: IContextActivation? = null

  fun activate() {
    if (contextActivation != null) {
      deactivate()
    }

    contextActivation = contextService.activateContext("com.gitlab.eclipse.codesuggestions.context")
  }

  fun deactivate() {
    contextActivation?.let { contextService.deactivateContext(it) }
    contextActivation = null
  }
}
