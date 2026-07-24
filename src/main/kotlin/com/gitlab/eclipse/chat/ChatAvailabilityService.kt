package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.utils.refreshDuoChatWindow
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService

/**
 * Aggregates the Language Server feature state of both classic (`chat`) and agentic
 * (`agentic_chat`) Duo Chat into per-webview [ChatAvailability] values plus the aggregate
 * `duo_chat_available` workbench source variable.
 *
 * State updates ([updateClassic] / [updateAgentic]) are pure field writes; the side effects
 * (view refresh, status re-render, source change) run asynchronously on the UI thread and only
 * refresh the view when the observable availability actually transitioned.
 */
class ChatAvailabilityService(
  private val onChanged: () -> Unit = {
    refreshDuoChatWindow()
    refreshDuoChatStatus()
  },
) : AbstractSourceProvider() {
  companion object {
    const val DUO_CHAT_AVAILABLE_KEY = "duo_chat_available"
    private const val CLASSIC_WEBVIEW_ID = "duo-chat-v2"
    private const val AGENTIC_WEBVIEW_ID = "agentic-duo-chat"
    private const val CLASSIC_FEATURE_ID = "chat"
  }

  private var classicChecks: List<FeatureStateChangeCheck>? = null
  private var agenticChecks: List<FeatureStateChangeCheck>? = null

  val anyChatEnabled: Boolean
    get() = enabled(classicChecks) || enabled(agenticChecks)

  fun updateClassic(change: FeatureStateChange) {
    update { classicChecks = change.allChecks }
  }

  fun updateAgentic(change: FeatureStateChange) {
    update { agenticChecks = change.allChecks }
  }

  fun availabilityFor(id: String): ChatAvailability = when (id) {
    CLASSIC_WEBVIEW_ID -> ChatAvailability(id, enabled(classicChecks), reason(classicChecks))
    AGENTIC_WEBVIEW_ID -> ChatAvailability(id, enabled(agenticChecks), reason(agenticChecks))
    else -> ChatAvailability(id, enabled = false, disabledReason = null)
  }

  private fun update(mutate: () -> Unit) {
    val previousState = snapshot()
    mutate()

    currentDisplay.asyncExec {
      if (previousState != snapshot()) {
        onChanged()
      }

      fireSourceChanged(ISources.WORKBENCH, DUO_CHAT_AVAILABLE_KEY, anyChatEnabled)
    }
  }

  private fun snapshot(): List<ChatAvailability> =
    listOf(availabilityFor(CLASSIC_WEBVIEW_ID), availabilityFor(AGENTIC_WEBVIEW_ID))

  /** Test-only: sets the checks for a feature without triggering the SWT side-effect path. */
  internal fun applyForTest(featureId: String, enabled: Boolean, reason: String?) {
    val checks = listOf(FeatureStateChangeCheck(checkId = "test", engaged = !enabled, details = reason))
    if (featureId == CLASSIC_FEATURE_ID) {
      classicChecks = checks
    } else {
      agenticChecks = checks
    }
  }

  override fun getCurrentState(): Map<Any?, Any?> = mapOf(DUO_CHAT_AVAILABLE_KEY to anyChatEnabled)

  override fun getProvidedSourceNames() = arrayOf(DUO_CHAT_AVAILABLE_KEY)

  override fun dispose() = Unit
}

private fun enabled(checks: List<FeatureStateChangeCheck>?): Boolean =
  checks?.none { it.engaged } ?: false

private fun reason(checks: List<FeatureStateChangeCheck>?): String? =
  checks?.firstOrNull { it.engaged }?.details

private fun refreshDuoChatStatus() {
  PlatformUI
    .getWorkbench()
    .getService(ICommandService::class.java)
    .refreshElements("gitlab-eclipse-plugin.commands.chatStatus", emptyMap<Any, Any>())
}
