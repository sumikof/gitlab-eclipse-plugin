package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.utils.refreshDuoChatWindow
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService

class DuoChatStateService : AbstractSourceProvider() {
  companion object {
    const val DUO_CHAT_ENABLED_KEY = "duo_chat_enabled"
  }

  private var checks: List<FeatureStateChangeCheck>? = null
  private val isEnabled: Boolean
    get() = checks?.none { it.engaged } ?: false

  fun update(featureStateChange: FeatureStateChange) {
    val previousState = isEnabled
    checks = featureStateChange.allChecks

    currentDisplay.syncExec {
      if (previousState != isEnabled) {
        refreshDuoChatWindow()
        refreshDuoChatStatus()
      }

      fireSourceChanged(ISources.WORKBENCH, DUO_CHAT_ENABLED_KEY, isEnabled)
    }
  }

  fun getFirstEngagedCheck(): FeatureStateChangeCheck? {
    return checks?.firstOrNull { it.engaged }
  }

  override fun getCurrentState(): Map<Any?, Any?> {
    return mapOf(DUO_CHAT_ENABLED_KEY to isEnabled)
  }

  override fun getProvidedSourceNames() = arrayOf(DUO_CHAT_ENABLED_KEY)

  override fun dispose() = Unit

  private fun refreshDuoChatStatus() {
    PlatformUI
      .getWorkbench()
      .getService(ICommandService::class.java)
      .refreshElements("gitlab-eclipse-plugin.commands.chatStatus", emptyMap<Any, Any>())
  }
}
