package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.webview.WebviewLoadCoordinator
import com.gitlab.eclipse.lsp.webview.WebviewUriResolver
import com.gitlab.eclipse.lsp.webview.forProduction
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformLayoutStore
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformReadiness
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformSurface
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformViews
import org.eclipse.swt.widgets.Composite
import org.eclipse.ui.part.ViewPart

/**
 * Hosts the Agent Platform webview in the layout the user chose (design AGENT-02 §5.1): the new
 * `root/duoAgentPlatform` page by default, or the classic `agentic-tabs` one. The id and the class
 * name stay those of the old `agentic-tabs` view, so saved perspectives and the existing commands
 * keep opening it.
 *
 * Which id is loaded, and when it is loaded again, is [AgentPlatformSurface]'s; this part only
 * builds the widgets and wires them. [AgentPlatformViews] reloads it when a new language server
 * becomes ready, which replaces the old "reopen the view" advice (design §23 U-4). The page stays on
 * the language server's origin under [LanguageServerOriginNavigationGuard]; an engine Reload / Back
 * that drops the CSRF token comes back here as a [reloadFromMenu]-equivalent re-resolution.
 * Everything here runs on the UI thread.
 */
class AgenticTabsView : ViewPart() {
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()
  private val layoutStore by lazyService<AgentPlatformLayoutStore>()
  private val readinessService by lazyService<AgentPlatformReadiness>()

  private var host: WebviewBrowserHost? = null
  private var surface: AgentPlatformSurface? = null

  override fun createPartControl(parent: Composite) {
    // Design §8.1's `asyncExec`, taken from the display that owns these widgets. The
    // WebviewLoadCoordinator contract allows this to fail only once that display is gone, which is
    // the one way `Display.asyncExec` fails.
    val display = parent.display
    // Resolved here, never in dispose (fable F8), like the store below.
    val readiness = readinessService
    val coordinator = WebviewLoadCoordinator(
      resolver = WebviewUriResolver.forProduction(languageServerWrapper),
      wrapper = languageServerWrapper,
      onUiThread = { display.asyncExec(it) },
    )

    val created = WebviewBrowserHost(
      parent,
      coordinator,
      navigationPolicy = LanguageServerOriginNavigationGuard(),
      // Posted by the host in a later UI turn; the surface is set by then, and is a no-op once disposed.
      onReloadRequested = { surface?.reload() },
      // Same UI turn as setUrl, so `expect` cannot clear an appReady that already arrived for this page (§5.4).
      onApplied = { session -> surface?.pageApplied(session, readiness::expect) },
    ) { partName = it }
    host = created
    // The store is resolved here, never in dispose (design AGENT-02 §5.2, fable F8): a view closed
    // during shutdown must not reach for Koin. The registry is a plain object and needs no lookup.
    val controller = AgentPlatformSurface(layoutStore, { id -> created.load(id) }, { created.displayedSession })
    surface = controller
    AgentPlatformViews.register(controller)
    controller.start()
  }

  /** The view menu's "Reload": re-resolves the current layout's page. UI thread only. */
  fun reloadFromMenu() {
    surface?.reload()
  }

  override fun setFocus() {
    host?.setFocus()
  }

  /** Listener first, then the registry entry, then the widgets (design AGENT-02 §5.1). */
  override fun dispose() {
    surface?.let {
      it.dispose()
      AgentPlatformViews.unregister(it)
    }
    surface = null
    host?.dispose()
    host = null
    super.dispose()
  }

  companion object {
    const val VIEW_ID = "com.gitlab.eclipse.views.webview.AgenticTabsView"
  }
}
