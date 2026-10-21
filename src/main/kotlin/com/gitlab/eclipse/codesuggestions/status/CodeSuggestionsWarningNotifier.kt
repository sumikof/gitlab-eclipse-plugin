package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.ActionNotifications
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.preference.IPersistentPreferenceStore
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import java.io.IOException

private const val POPUP_TITLE = "GitLab Duo"
private const val NO_CREDITS_AUTO_CLOSE_MILLIS = 10_000L

/** The upgrade documentation the version warning links to (design §7 W1). */
internal const val UPGRADE_DOCS_URL = "https://docs.gitlab.com/update/"

/** What one CS-08 popup shows; [links] are (label, action) pairs run on the UI thread. */
data class WarningPresentation(
  val title: String,
  val message: String,
  val severity: Int,
  val links: List<Pair<String, () -> Unit>>,
  val autoCloseMillis: Long,
)

/**
 * Displays the CS-08 Code Suggestions warnings (design §7) that [policy] decides on.
 *
 * [onFeatureState] runs on the feature-state worker thread. An event of a session that is not the
 * current connection, or one arriving after the lifecycle store closed, is dropped **before** the
 * policy sees it: the policy resets its per-session state whenever the session differs, so a stale
 * event would flip it back and forth. The UI runnable re-checks both (W6). Never logs the instance
 * URL, the version or the text; failures are logged by exception class name only.
 *
 * @param openUpgradeDocs opens [UPGRADE_DOCS_URL] in the external browser (the existing
 *   `ShowDocumentLauncher` path); runs on the UI thread from a link
 * @param present opens the popup; runs inside the UI runnable
 */
class CodeSuggestionsWarningNotifier(
  private val policy: CodeSuggestionsWarningPolicy,
  private val currentSession: () -> LanguageServerSession?,
  private val isClosed: () -> Boolean,
  private val openUpgradeDocs: () -> Unit,
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val present: (WarningPresentation) -> Unit = ::presentWarning,
) {
  private val log by lazy { logger<CodeSuggestionsWarningNotifier>() }

  /** Total: never throws to the dispatching thread. */
  fun onFeatureState(session: LanguageServerSession, checks: List<FeatureStateChangeCheck>?) {
    try {
      if (!isCurrent(session)) return
      val warning = policy.onFeatureState(session, checks) ?: return
      val presentation = presentationOf(warning)
      uiDispatch(
        Runnable {
          try {
            if (isCurrent(session)) present(presentation)
          } catch (ignored: SWTException) {
            /* display disposed mid-turn: nothing to show */
          }
        },
      )
    } catch (e: RuntimeException) {
      // A disposed display (SWTException), a torn-down workbench (IllegalStateException) or a
      // failing preference read: the worker thread must not see it.
      warnSkipped(e)
    }
  }

  private fun isCurrent(session: LanguageServerSession): Boolean = !isClosed() && session === currentSession()

  private fun presentationOf(warning: CodeSuggestionsWarning): WarningPresentation = when (warning) {
    is CodeSuggestionsWarning.UnsupportedVersion -> WarningPresentation(
      title = POPUP_TITLE,
      message = "GitLab Duo Code Suggestions requires GitLab version 16.8.0 or later. " +
        "GitLab instance located at: ${warning.baseUrl} is currently using ${warning.version}.",
      severity = SWT.ICON_WARNING,
      links = listOf(
        "Learn about upgrading" to contained { openUpgradeDocs() },
        "Do not show again" to contained { policy.suppressVersionWarning(warning.baseUrl) },
      ),
      // Stays until closed (AbstractNotificationPopup auto-closes only when > 0).
      autoCloseMillis = 0,
    )
    CodeSuggestionsWarning.NoCredits -> WarningPresentation(
      title = POPUP_TITLE,
      message = "No GitLab Credits remain for this billing period. To continue using Code Suggestions, " +
        "contact your administrator. When you have more credits, restart the GitLab Language Server.",
      severity = SWT.ICON_INFORMATION,
      links = listOf("Dismiss" to contained { policy.dismissNoCreditsToday() }),
      autoCloseMillis = NO_CREDITS_AUTO_CLOSE_MILLIS,
    )
  }

  /**
   * A link action runs in an SWT selection listener: an escaping exception would reach the event
   * loop, which logs its message. Contained, class name only.
   */
  private fun contained(action: () -> Unit): () -> Unit = {
    try {
      action()
    } catch (e: RuntimeException) {
      runCatching { log.warn("A Code Suggestions warning action failed (${e.javaClass.simpleName}).") }
    }
  }

  private fun warnSkipped(e: Exception) {
    runCatching { log.warn("Skipped a Code Suggestions warning (${e.javaClass.simpleName}).") }
  }
}

/** Default [CodeSuggestionsWarningNotifier] presenter: runs inside the notifier's UI runnable. */
private fun presentWarning(presentation: WarningPresentation) {
  ActionNotifications.show(
    title = presentation.title,
    message = presentation.message,
    severity = presentation.severity,
    links = presentation.links,
    autoCloseMillis = presentation.autoCloseMillis,
    // Already on the UI thread: the re-check above and the popup stay in the same UI turn.
    onUiThread = { it.run() },
  )
}

/**
 * [WarningPreferenceAccess] over the plugin's preference store (the Koin `ScopedPreferenceStore`,
 * InstanceScope, ruling R1). A write is `setValue` followed by a best-effort `save()`; a failed save
 * is logged by exception class name only (the message may quote the preference file's path).
 */
class ScopedPreferenceWarningAccess(private val store: IPersistentPreferenceStore) : WarningPreferenceAccess {
  override fun get(key: String): String = store.getString(key)

  override fun set(key: String, value: String) {
    store.setValue(key, value)
    try {
      store.save()
    } catch (e: IOException) {
      runCatching {
        logger<ScopedPreferenceWarningAccess>().warn("Could not save a warning preference (${e.javaClass.simpleName}).")
      }
    }
  }
}
