package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.logger

/**
 * The `appReady` diagnostic of design §5.4.
 *
 * When a page for a session has been applied (`setUrl` done), the webview is expected to complete
 * its handshake and the language server to forward `appReady` for it. If none arrives within
 * [TIMEOUT_MS] while that session is still current, one WARN is logged — a hint that the SWT
 * browser engine failed the handshake. The page is never replaced: with several windows an
 * `appReady` cannot be attributed to one view, and a false positive must not wipe a working page.
 *
 * UI-thread-only by contract (the controller hops before [markReady]; the pipeline applies on the
 * UI thread; production [schedule] is `Display.timerExec`), so there is no synchronization.
 */
class AgentPlatformReadiness(
  private val schedule: (delayMs: Long, Runnable) -> Unit,
  private val currentSession: () -> LanguageServerSession?,
) {
  private val logger by lazy { logger<AgentPlatformReadiness>() }

  /** Bumped by every [expect]; a check whose generation is no longer the latest does nothing. */
  private var generation = 0L
  private var readySession: LanguageServerSession? = null

  fun markReady(session: LanguageServerSession) {
    readySession = session
  }

  /**
   * Called when a page for [session] was applied. After [TIMEOUT_MS], if no [markReady] for
   * [session] followed and [session] is still current, logs one WARN. The URL is never logged: it
   * carries the CSRF token (design §9).
   *
   * An earlier `appReady` is forgotten here: it belonged to the page this one replaced.
   */
  fun expect(session: LanguageServerSession) {
    val expected = ++generation
    readySession = null
    schedule(TIMEOUT_MS, Runnable { check(expected, session) })
  }

  private fun check(expected: Long, session: LanguageServerSession) {
    if (expected != generation) return
    if (currentSession() !== session) return
    if (readySession === session) return
    logger.warn(WARNING)
  }

  private companion object {
    /** The default of the reference extension's `gitlab.webviewTimeoutSeconds`. */
    const val TIMEOUT_MS = 10_000L
    const val WARNING = "Agent Platform: no appReady within 10 s"
  }
}
