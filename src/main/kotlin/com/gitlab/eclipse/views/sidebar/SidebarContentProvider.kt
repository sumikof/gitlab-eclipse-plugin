package com.gitlab.eclipse.views.sidebar

import org.eclipse.jface.viewers.ITreeContentProvider

/**
 * Maps a `List<SidebarNode>` input (the query roots) onto JFace's tree callbacks.
 * Purely structural: every [SidebarNode] exposes its own [SidebarNode.children],
 * so no dispatch on concrete node types is needed here.
 */
class SidebarContentProvider : ITreeContentProvider {
  override fun getElements(inputElement: Any?): Array<Any> =
    ((inputElement as? List<*>)?.filterIsInstance<SidebarNode>() ?: emptyList()).toTypedArray<Any>()

  override fun getChildren(parentElement: Any?): Array<Any> =
    ((parentElement as? SidebarNode)?.children ?: emptyList()).toTypedArray<Any>()

  override fun getParent(element: Any?): Any? = null

  override fun hasChildren(element: Any?): Boolean =
    (element as? SidebarNode)?.children?.isNotEmpty() ?: false
}
