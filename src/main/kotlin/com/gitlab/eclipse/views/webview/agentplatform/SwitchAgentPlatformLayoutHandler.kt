package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Shared body of the two layout toggles (design AGENT-02 §6): sets [layout] on the store, whose
 * listeners reload the view and republish `gitlab_agent_platform_layout`.
 *
 * Runs on the thread the workbench dispatches commands on, which is the UI thread the store
 * requires; nothing here asserts it. A failed write (the preference store) is logged by exception
 * class only and reported, since the toolbar would otherwise silently keep offering the same toggle.
 */
open class SwitchAgentPlatformLayoutHandler(
  private val layout: AgentPlatformLayout,
  private val layoutStore: () -> AgentPlatformLayoutStore,
  private val notify: (String) -> Unit,
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    try {
      layoutStore().set(layout)
    } catch (e: Exception) {
      val failure = e::class.java.name
      logger<SwitchAgentPlatformLayoutHandler>().error("Agent Platform layout switch to $layout failed: $failure")
      notify(AgentPlatformMessages.LAYOUT_SWITCH_FAILURE)
    }
    return null
  }
}
