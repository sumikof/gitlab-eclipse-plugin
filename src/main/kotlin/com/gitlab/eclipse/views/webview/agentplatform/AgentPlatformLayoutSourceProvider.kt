package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources

/**
 * Source provider for `gitlab_agent_platform_layout` (`"new"` / `"classic"`), which decides which of
 * the two layout toggles the Agent Platform view's toolbar and pulldown show: only the one leading
 * away from the current layout, as the reference extension's view title does (design AGENT-02 §6).
 *
 * [getCurrentState] reads the store at call time, so the workbench's first evaluation is right
 * without any change notification. The store subscription is taken on that first read rather than
 * at construction: the workbench reads the state as it registers the provider, on the UI thread
 * the store requires, and the constructor stays free of Koin lookups.
 *
 * Instantiated by the workbench from `plugin.xml`; the parameters are seams for headless tests.
 *
 * @param layoutStore the shared layout store; looked up on first use.
 * @param uiDispatch hands a runnable to the UI thread; `fireSourceChanged` runs there, in a later
 *   turn, so the toolbar is re-evaluated after the command that changed the layout has returned.
 */
class AgentPlatformLayoutSourceProvider(
  private val layoutStore: () -> AgentPlatformLayoutStore = { service() },
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
) : AbstractSourceProvider() {
  companion object {
    const val LAYOUT_KEY = "gitlab_agent_platform_layout"
  }

  // UI-thread-only, like the store it mirrors.
  private var subscription: AutoCloseable? = null
  private var disposed = false

  override fun getCurrentState(): Map<String, String> {
    subscribeOnce()
    return mapOf(LAYOUT_KEY to currentValue())
  }

  override fun getProvidedSourceNames(): Array<String> = arrayOf(LAYOUT_KEY)

  override fun dispose() {
    disposed = true
    subscription?.close()
    subscription = null
  }

  private fun currentValue(): String = layoutStore().current().preferenceValue

  private fun subscribeOnce() {
    if (disposed || subscription != null) return
    subscription = layoutStore().addListener { fire() }
  }

  /** The value is read at firing time, so queued fires never publish a superseded layout. */
  private fun fire() {
    uiDispatch {
      if (!disposed) fireSourceChanged(ISources.WORKBENCH, LAYOUT_KEY, currentValue())
    }
  }
}
