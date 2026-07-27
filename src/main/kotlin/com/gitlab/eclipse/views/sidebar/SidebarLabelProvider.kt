package com.gitlab.eclipse.views.sidebar

import org.eclipse.jface.viewers.ColumnLabelProvider

/** Renders each [SidebarNode] by its precomputed [SidebarNode.label]. */
class SidebarLabelProvider : ColumnLabelProvider() {
  override fun getText(element: Any?): String = (element as? SidebarNode)?.label ?: ""
}
