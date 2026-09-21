package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.lsp.FeatureStateChange
import java.util.concurrent.ConcurrentHashMap

/**
 * The last feature state the language server reported, per feature (design §8.3).
 *
 * Kept separately from `DuoChatStateService` and `ChatAvailabilityService` on purpose. Those two
 * hold the subset of state the UI binds to, behind private fields, and each drops the checks it
 * does not need; the client's dispatch drops whole features it does not model (`flows`, `sandbox`,
 * `agent_platform`). A diagnostics report wants exactly what was dropped — a feature the plugin
 * ignores is a prime suspect when something does not work — so this records **every** change,
 * before that dispatch narrows it.
 *
 * Concurrent: `$/gitlab/featureStateChange` arrives on lsp4j's dispatch thread while a diagnostics
 * command may be collecting on another. A [ConcurrentHashMap] of immutable values is enough — a
 * report that catches one feature a moment out of date is still a correct report (design §13).
 */
object FeatureStateStore {

  private val states = ConcurrentHashMap<String, FeatureStateChange>()

  /** Records [change] as the current state of its feature, replacing any earlier one. */
  fun record(change: FeatureStateChange) {
    states[change.featureId] = change
  }

  /**
   * Every recorded feature, in the reference extension's presentation order, titled as it titles
   * them. Features the server has never mentioned are absent; features this table does not name
   * still appear, under their wire id, for the same reason unknown check ids do.
   */
  fun snapshots(): List<FeatureStateSnapshot> {
    val known = ORDER.mapNotNull { id -> states[id]?.let { snapshot(id, it) } }
    val unknown = states.keys.sorted().filterNot { it in ORDER }.mapNotNull { id ->
      states[id]?.let { snapshot(id, it) }
    }
    return known + unknown
  }

  /** Test seam: drops everything recorded so far. */
  internal fun clear() = states.clear()

  private fun snapshot(id: String, change: FeatureStateChange) =
    FeatureStateSnapshot(TITLES[id] ?: id, change.allChecks.orEmpty())

  /**
   * Feature ids as the bundled language server defines them, read out of the shipped bundle
   * (`const CODE_SUGGESTIONS = "code_suggestions"` and siblings in `build/gitlab-lsp/bin/main.js.map`),
   * not from memory. The order matches the reference's `FeatureStateDiagnosticsRenderer`.
   */
  private val ORDER = listOf(
    "authentication",
    "code_suggestions",
    "chat",
    "chat_terminal_context",
    "agentic_chat",
    "agent_platform",
    "flows",
    "sandbox",
  )

  private val TITLES = mapOf(
    "authentication" to "Authentication",
    "code_suggestions" to "GitLab Duo Code Suggestions",
    "chat" to "GitLab Duo Non-Agentic Chat",
    "chat_terminal_context" to "Terminal Context",
    "agentic_chat" to "GitLab Duo Agentic Chat",
    "agent_platform" to "GitLab Duo Agent Platform",
    "flows" to "GitLab Flows",
    "sandbox" to "GitLab Duo Agent Sandboxing",
  )
}
