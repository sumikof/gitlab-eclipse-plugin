package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils

/**
 * "Use New Layout" (`com.gitlab.eclipse.commands.UseNewAgentPlatformLayout`). Instantiated by the
 * workbench from `plugin.xml`; the parameters are seams for headless tests.
 */
class UseNewAgentPlatformLayoutHandler(
  layoutStore: () -> AgentPlatformLayoutStore = { service() },
  notify: (String) -> Unit = { NotificationUtils.show(it) },
) : SwitchAgentPlatformLayoutHandler(AgentPlatformLayout.NEW, layoutStore, notify)
