package com.gitlab.eclipse.views.sidebar

/** How the sidebar renders its nodes. */
enum class SidebarViewMode { LIST, TREE }

/**
 * Holds the sidebar's current [SidebarViewMode] and notifies listeners when it actually
 * changes (setting the same value again is a no-op). Not persisted across sessions.
 */
class SidebarViewState(initial: SidebarViewMode = SidebarViewMode.LIST) {
  private val listeners = mutableListOf<() -> Unit>()

  var mode: SidebarViewMode = initial
    set(value) {
      if (field == value) return
      field = value
      listeners.forEach { it() }
    }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }
}
