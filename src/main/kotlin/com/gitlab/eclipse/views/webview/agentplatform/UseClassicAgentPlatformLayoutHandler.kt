package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils

/**
 * "Use Classic Layout" (`com.gitlab.eclipse.commands.UseClassicAgentPlatformLayout`). Instantiated by the
 * workbench from `plugin.xml`; the parameters are seams for headless tests.
 */
class UseClassicAgentPlatformLayoutHandler(
  layoutStore: () -> AgentPlatformLayoutStore = { service() },
  notify: (String) -> Unit = { NotificationUtils.show(it) },
) : SwitchAgentPlatformLayoutHandler(AgentPlatformLayout.CLASSIC, layoutStore, notify)
