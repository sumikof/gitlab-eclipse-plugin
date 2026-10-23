package com.gitlab.eclipse.views.sidebar

import org.eclipse.core.expressions.PropertyTester

/** Visibility of the sidebar "Open in Browser" / "Copy Link" actions: [HAS_WEB_URL_PROPERTY] is true when the node has an openable web URL. */
class SidebarLinkPropertyTester : PropertyTester() {
  override fun test(receiver: Any?, property: String?, args: Array<out Any?>?, expectedValue: Any?): Boolean =
    when (property) {
      HAS_WEB_URL_PROPERTY -> receiver is SidebarNode && sidebarWebUrl(receiver) != null
      else -> false
    }
}
