package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.inject.lazyService
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources

/**
 * Exposes the shared [SidebarViewState] mode as the `gitlab.sidebarView` workbench source
 * variable (`"list"` / `"tree"`) so the sidebar toolbar's list/tree toggle buttons can use
 * `visibleWhen` core expressions.
 *
 * The chain: a mode handler sets [SidebarViewState.mode] → the state notifies its listeners →
 * this provider fires a source change → the workbench re-evaluates the toolbar `visibleWhen`
 * expressions. Mode writes happen on the UI thread (command handlers), so the source change
 * fires on the UI thread as well.
 *
 * Instantiated by the workbench from plugin.xml (`org.eclipse.ui.services`); resolves the SAME
 * Koin-managed [SidebarViewState] singleton the view and handlers use.
 */
class SidebarViewStateSourceProvider : AbstractSourceProvider() {
  companion object {
    const val SIDEBAR_VIEW_KEY = "gitlab.sidebarView"
  }

  private val viewState by lazyService<SidebarViewState>()

  // Stored so dispose() can remove this exact instance from the shared SidebarViewState.
  private val modeListener: () -> Unit = {
    fireSourceChanged(ISources.WORKBENCH, SIDEBAR_VIEW_KEY, currentValue())
  }

  init {
    viewState.addListener(modeListener)
  }

  private fun currentValue(): String = when (viewState.mode) {
    SidebarViewMode.LIST -> "list"
    SidebarViewMode.TREE -> "tree"
  }

  override fun getCurrentState(): Map<String, String> = mapOf(SIDEBAR_VIEW_KEY to currentValue())

  override fun getProvidedSourceNames() = arrayOf(SIDEBAR_VIEW_KEY)

  override fun dispose() {
    viewState.removeListener(modeListener)
  }
}
