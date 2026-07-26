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
      // Snapshot so a listener that adds/removes listeners (or flips the mode again)
      // does not cause a ConcurrentModificationException mid-iteration.
      listeners.toList().forEach { it() }
    }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }
}
