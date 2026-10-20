package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.lsp.LanguageServerSession

/** What [AgentPlatformViews] needs from one open view. UI thread only. */
interface ReloadableWebviewSurface {
  /** The session whose page is on screen, or `null` when none is (message page, never loaded). */
  val displayedSession: LanguageServerSession?

  /** Re-resolves the page through the language server's webview metadata. */
  fun reload()
}

/**
 * The SWT-free controller of one Agent Platform view (design AGENT-02 §5.1): it decides which
 * webview id the view's host loads, following [layoutStore], and is what the registry and the
 * view's "Reload" action reload. UI thread only, like the store that notifies it.
 *
 * [load] is the host's `load(id)`, which resolves `$/gitlab/webview-metadata` afresh every time; the
 * host's pipeline is latest-wins, so a layout switch during a load still ends on the last id asked
 * for. [displayedSessionOf] reads the host's displayed session.
 */
class AgentPlatformSurface(
  private val layoutStore: AgentPlatformLayoutStore,
  private val load: (webviewId: String) -> Unit,
  private val displayedSessionOf: () -> LanguageServerSession?,
) : ReloadableWebviewSurface {
  private var subscription: AutoCloseable? = null
  private var started = false
  private var disposed = false

  /** The id last passed to [load], or `null` before [start]. */
  var loadedWebviewId: String? = null
    private set

  override val displayedSession: LanguageServerSession?
    get() = displayedSessionOf()

  /** Subscribes to layout changes and loads the current layout's id. Only the first call acts. */
  fun start() {
    if (disposed || started) return
    started = true
    subscription = layoutStore.addListener(::onLayoutChanged)
    loadId(layoutStore.current().webviewId)
  }

  /** Loads the current layout's id again (metadata re-resolution). No-op after [dispose]. */
  override fun reload() {
    if (disposed) return
    loadId(layoutStore.current().webviewId)
  }

  /**
   * The host's pipeline put a page for [session] on screen (same UI turn as `setUrl`). Arms the
   * `appReady` diagnostic through [expectAppReady] only for the new layout: the classic
   * `agentic-tabs` page sends no `appReady` to the IDE, so arming for it would always warn
   * (design §5.4). [loadedWebviewId] is the applied page's id, because the pipeline settles only
   * the latest load. No-op after [dispose].
   */
  fun pageApplied(session: LanguageServerSession, expectAppReady: (LanguageServerSession) -> Unit) {
    if (disposed || loadedWebviewId != AgentPlatformLayout.NEW_WEBVIEW_ID) return
    expectAppReady(session)
  }

  /** Unsubscribes; afterwards [start], [reload] and layout events are no-ops. Idempotent. */
  fun dispose() {
    if (disposed) return
    disposed = true
    subscription?.close()
    subscription = null
  }

  private fun onLayoutChanged(layout: AgentPlatformLayout) {
    if (disposed || layout.webviewId == loadedWebviewId) return
    loadId(layout.webviewId)
  }

  private fun loadId(id: String) {
    loadedWebviewId = id
    load(id)
  }
}
