package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.logger

/**
 * Every open Agent Platform view, across workbench windows, so a language server that becomes
 * ready can re-resolve the pages still bound to an earlier one (design AGENT-02 §5.2, the old U-4).
 *
 * Confined to the UI thread, so its state is plain fields: views register from `createPartControl`
 * and unregister from `dispose`, the readiness callback calls [reloadStale] from an `asyncExec`,
 * and the bundle's stop / start UI turns call [onDeactivate] / [onActivate]. Registration is
 * explicit, not weak: a view that is disposed has always unregistered itself first. It reads
 * nothing from Koin; the caller passes the session.
 */
object AgentPlatformViews {
  private val surfaces = mutableListOf<ReloadableWebviewSurface>()
  private var active = true

  /** Adds [surface] once; ignored while inactive. */
  fun register(surface: ReloadableWebviewSurface) {
    if (!active || surfaces.any { it === surface }) return
    surfaces += surface
  }

  fun unregister(surface: ReloadableWebviewSurface) {
    surfaces.removeAll { it === surface }
  }

  /**
   * Reloads every registered surface whose displayed session is `null` or not [current]. With no
   * [current] (the server is gone) nothing is reloaded: the page shows its own "Connection lost"
   * and the next ready reloads it. A surface already on [current] — loaded during the start-up
   * window, or by an earlier call — is left alone, which makes a repeat call a no-op. One surface's
   * failure is logged by class name and does not stop the others. No-op while inactive.
   */
  fun reloadStale(current: LanguageServerSession?) {
    if (!active || current == null) return
    for (surface in surfaces.toList()) {
      try {
        if (surface.displayedSession !== current) surface.reload()
      } catch (e: Exception) {
        // Class name only: a message could carry the page url and its CSRF token.
        logger<AgentPlatformViews>().warn("Agent Platform view reload failed: ${e::class.java.name}")
      }
    }
  }

  /** The bundle start's UI turn: accept registrations again. */
  fun onActivate() {
    active = true
  }

  /** The bundle stop's UI turn: forget every view and refuse [register] / [reloadStale] until [onActivate]. */
  fun onDeactivate() {
    active = false
    surfaces.clear()
  }
}
