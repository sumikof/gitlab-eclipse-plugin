package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.swt.SWTException
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService

/**
 * The `code_suggestions` feature state of the current Language Server connection.
 *
 * `checks` is null until the connection's first feature state arrives and again after [reset] (the
 * connection is gone), so the status shows "Checking..." for a restarted server (design §8.3).
 *
 * [onStatusChanged] fires whenever [featureStateKnown] or [primaryCheckId] changes (not only on an
 * [isEnabled] flip), on the updating thread; it must not block. [onFeatureState] receives every
 * accepted update of the current session (the CS-08 warning notifier) and must not throw.
 *
 * @param currentSession the session of the current connection, or null when there is none; an
 *   update of any other session is dropped before it touches the state or the warnings.
 */
class CodeSuggestionsStateService(
  private val currentSession: () -> LanguageServerSession? = {
    service<GitLabLanguageServerWrapper>().currentSnapshot?.session
  },
  private val onStatusChanged: () -> Unit = ::requestStatusRefresh,
  private val onFeatureState: (LanguageServerSession, List<FeatureStateChangeCheck>?) -> Unit = { _, _ -> },
) {
  private val lock = Any()

  @Volatile
  private var checks: List<FeatureStateChangeCheck>? = null

  val isEnabled: Boolean
    get() = checks?.none { it.engaged } ?: false

  /** Whether a feature state has arrived for the current connection. */
  val featureStateKnown: Boolean
    get() = checks != null

  /** The id of the first engaged check in the server's (priority) order, or null. */
  val primaryCheckId: String?
    get() = getFirstEngagedCheck()?.checkId

  /**
   * Accepts a `code_suggestions` feature state reported by [session]. Dropped when [session] is not
   * the current connection; the check is repeated under the lock that [reset] takes, so an update
   * racing the teardown of its connection cannot repopulate the state after the reset.
   */
  fun update(featureStateChange: FeatureStateChange, session: LanguageServerSession) {
    val changed = synchronized(lock) {
      if (session !== currentSession()) return
      replaceChecks(featureStateChange.allChecks)
    }
    if (changed) onStatusChanged()
    onFeatureState(session, featureStateChange.allChecks)
  }

  /** Session-less update; kept for callers that predate the session gate. */
  fun update(featureStateChange: FeatureStateChange) {
    val changed = synchronized(lock) { replaceChecks(featureStateChange.allChecks) }
    if (changed) onStatusChanged()
  }

  /** The connection is gone: back to "unknown" until the next connection reports. */
  fun reset() {
    val changed = synchronized(lock) { replaceChecks(null) }
    if (changed) onStatusChanged()
  }

  fun getFirstEngagedCheck() = checks?.firstOrNull { it.engaged }

  /** The ids of every engaged check, in the server's order; empty until the first feature state arrives. */
  fun engagedCheckIds(): List<String> = checks.orEmpty().filter { it.engaged }.map { it.checkId }

  /** Replaces the checks; returns whether the status (known / primary check id) changed. Under [lock]. */
  private fun replaceChecks(newChecks: List<FeatureStateChangeCheck>?): Boolean {
    val before = featureStateKnown to primaryCheckId
    checks = newChecks
    return before != (featureStateKnown to primaryCheckId)
  }
}

/**
 * Default [CodeSuggestionsStateService] status refresh: re-renders the Code Suggestions status menu
 * item. Total: a disposed display or a torn-down workbench means there is nothing to refresh.
 */
private fun requestStatusRefresh() {
  try {
    currentDisplay.asyncExec {
      try {
        PlatformUI
          .getWorkbench()
          .getService(ICommandService::class.java)
          .refreshElements("gitlab-eclipse-plugin.commands.codeSuggestionsStatus", emptyMap<Any, Any>())
      } catch (ignored: IllegalStateException) {
        /* workbench closing: nothing to refresh */
      } catch (ignored: SWTException) {
        /* display disposed mid-turn: nothing to refresh */
      }
    }
  } catch (ignored: SWTException) {
    /* asyncExec on a disposed display: nothing to refresh */
  } catch (ignored: IllegalStateException) {
    /* workbench torn down (background-thread display lookup): nothing to refresh */
  }
}
