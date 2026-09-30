package com.gitlab.eclipse.chat.terminal

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources
import java.util.concurrent.atomic.AtomicReference

/**
 * Source provider for `duo_chat_terminal_context_enabled`, the gate of "Explain Terminal Output
 * with Duo".
 *
 * Fed by the language server's `chat_terminal_context` feature, whose checks are all of `chat`'s
 * plus `chat-include-terminal-context-unavailable` (the user's `include_terminal_context` Duo
 * feature). It is the reference extension's condition for the same command. The server does not
 * apply that policy to a prompt's `fileContext`, so this gate is the only place it is enforced — the
 * handler therefore re-checks it when it runs, since a menu item that is still showing proves
 * nothing.
 *
 * The value is bound to the connection that reported it: [isEnabled] is `true` only while that
 * connection is still the current one, so a restart or a stop answers `false` until the new
 * connection reports. A late report from a replaced connection is dropped by compare-and-set, so it
 * cannot overwrite the new connection's value.
 *
 * Instantiated by the workbench from `plugin.xml`; the parameters are seams for headless tests.
 *
 * @param currentSession the session of the current connection, or null when there is none.
 * @param uiDispatch hands a runnable to the UI thread; `fireSourceChanged` must run there.
 */
class TerminalContextSourceProvider(
  private val currentSession: () -> LanguageServerSession? = {
    service<GitLabLanguageServerWrapper>().currentSnapshot?.session
  },
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
) : AbstractSourceProvider() {
  companion object {
    const val ENABLED_KEY = "duo_chat_terminal_context_enabled"
    const val FEATURE_ID = "chat_terminal_context"
  }

  private class Held(val session: LanguageServerSession, val enabled: Boolean)

  private val held = AtomicReference<Held?>(null)

  /** Whether the current connection has said the feature is available. */
  val isEnabled: Boolean
    get() {
      val current = held.get() ?: return false
      return current.enabled && current.session === currentSession()
    }

  /** Stores [change] as reported by [session], unless [session] is no longer the current connection. */
  fun update(change: FeatureStateChange, session: LanguageServerSession) {
    val checks = change.allChecks ?: return
    val next = Held(session, checks.none { it.engaged })
    while (true) {
      // Read first, check second: a write from the new connection that lands between the two makes
      // the compare-and-set fail, and the re-check then drops this one.
      val current = held.get()
      if (session !== currentSession()) return
      if (held.compareAndSet(current, next)) break
    }
    fire()
  }

  /** The connection is gone: forget its value and republish, so the menu item hides at once. */
  fun reset() {
    held.set(null)
    fire()
  }

  override fun getCurrentState(): Map<Any?, Any?> = mapOf(ENABLED_KEY to isEnabled)

  override fun getProvidedSourceNames(): Array<String> = arrayOf(ENABLED_KEY)

  override fun dispose() = Unit

  /** The value is computed at firing time, on the UI thread, with the connection check applied. */
  private fun fire() {
    uiDispatch { fireSourceChanged(ISources.WORKBENCH, ENABLED_KEY, isEnabled) }
  }
}
