// The file groups the CI lint launch skeleton (background launch + total UI helpers) shared by
// both lint handlers; the file name reflects the feature, not a single type.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.CiLintService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.model.CiLintResult
import com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry
import com.gitlab.eclipse.ci.lint.CiLintKey
import com.gitlab.eclipse.ci.lint.CiLintOutcome
import com.gitlab.eclipse.ci.lint.buildCiLintAuditMessage
import com.gitlab.eclipse.ci.lint.runCiLint
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.runtime.ILog
import org.eclipse.swt.SWTException

/** Generic, body-free failure message shared by every CI lint failure path (design §8.5). */
internal const val GENERIC_CI_LINT_ERROR = "GitLab: Couldn't validate the CI configuration. Try again."

/**
 * Pins the connection and runs one CI lint in the background (top-level on purpose — same shape
 * as [launchDisplayJobLog]). The pin ([GitLabApiClient.captureConnectionIf]) runs INSIDE the
 * launch, on the scope's IO dispatcher: once the instance url matches, capturing the connection
 * reads the token, which in OAuth mode may perform a synchronous refresh HTTP call that must
 * never block the UI thread. The instance-url comparison is bound here as the capture predicate
 * so it runs before the credential is read and a mismatched url never triggers that refresh
 * (#49); the capture → same-instance gate → lint sequencing lives in [runCiLint], which re-checks
 * the url as a postcondition. A gate failure means the yaml is NEVER sent to the wrong instance —
 * notify instead. [myGen] was assigned on the UI
 * thread by the caller; [onLinted] runs on the UI thread only after the latest-generation gate
 * passes. CancellationException is rethrown so cancellation propagates cleanly and neither
 * reflects nor notifies; any other unclassified throwable is caught here — it must never escape
 * and cancel the shared scope. Exactly one audit line ([buildCiLintAuditMessage]) is logged per
 * run regardless of the gate outcome (info on success, error otherwise); it never contains a
 * token, yaml body, or response body.
 */
internal fun launchCiLint(
  scope: CoroutineScope,
  log: ILog,
  apiClient: GitLabApiClient,
  service: CiLintService,
  context: RepositoryContext,
  content: String,
  key: CiLintKey,
  myGen: Long,
  onLinted: (CiLintResult) -> Unit,
) {
  scope.launch {
    // Shared scope with a plain Job: nothing may escape this launch or every other
    // consumer of the scope loses its coroutines.
    try {
      val outcome = runCiLint(
        contextInstanceUrl = context.instanceUrl,
        capture = { apiClient.captureConnectionIf { url -> sameConfiguredInstance(context.instanceUrl, url) } },
        lint = { connection -> service.validate(connection, context.projectId, content) },
      )
      val audit = buildCiLintAuditMessage(context.instanceUrl, context.projectId, key.command, outcome)
      when (outcome) {
        is CiLintOutcome.Linted -> {
          log.info(audit)
          reflectOnUiThread(log, key, myGen) { onLinted(outcome.result) }
        }
        else -> {
          // InstanceMismatch / ConnectionUnstable / Failed: audited above; one generic popup.
          log.error(audit)
          notifyIfLatest(key, myGen, GENERIC_CI_LINT_ERROR)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Unclassified escape: must not cancel the shared scope. Same token/body-free structured
      // audit line as the classified path. The exception object itself must NOT be attached to
      // the log entry: an invalid HTTP-header character in the captured token makes
      // HttpRequest.Builder.header("Authorization", ...) throw an IllegalArgumentException whose
      // message embeds the full "Bearer <token>" value, and it lands here unclassified — logging
      // e (message/stacktrace/cause) would persist the token in the Eclipse Error Log. Only the
      // exception TYPE is appended; that is safe and still identifies the failure shape.
      val audit = buildCiLintAuditMessage(
        context.instanceUrl,
        context.projectId,
        key.command,
        CiLintOutcome.Failed(
          WriteOutcome.Failure(
            httpStatus = null,
            correlationId = null,
            failureKind = "unexpected",
          ),
        ),
      )
      log.error(audit + " exceptionType=" + e.javaClass.name)
      notifyIfLatest(key, myGen, GENERIC_CI_LINT_ERROR)
    }
  }
}

/**
 * Marshals the lint success to the UI thread and runs [action] ONLY if this run is still the
 * active activation's latest generation for [key] (design §8.5). The gate and the reflect happen
 * in the same UI turn, so they are atomic w.r.t. any newer run. asyncExec itself can throw
 * [SWTException] on a disposed Display — swallowed, never allowed to cancel the shared scope. The
 * `currentDisplay` lookup itself can throw [IllegalStateException] from this background thread
 * once the workbench is torn down — also swallowed: the helper must be total (never throw), or
 * the launch's terminal catch would propagate it and cancel the shared scope. The in-UI fallback
 * notification is itself wrapped in a [SWTException] catch so a display disposed mid-turn cannot
 * escape the runnable either.
 */
private fun reflectOnUiThread(log: ILog, key: CiLintKey, myGen: Long, action: () -> Unit) {
  try {
    currentDisplay.asyncExec {
      try {
        if (currentDisplay.isDisposed) return@asyncExec
        if (!CiLintGenerationRegistry.shouldAct(key, myGen)) return@asyncExec
        action()
      } catch (ignored: SWTException) {
        /* display disposed mid-turn: no-op */
      } catch (e: Exception) {
        // The reflect (dialog/editor open) can throw in-UI where the background catch can't see it.
        log.error("CI lint UI reflect failed for $key", e)
        if (CiLintGenerationRegistry.shouldAct(key, myGen)) {
          try {
            NotificationUtils.showOnUiThread(GENERIC_CI_LINT_ERROR)
          } catch (ignored: SWTException) {
            /* display disposed mid-turn: no-op */
          }
        }
      }
    }
  } catch (ignored: SWTException) {
    /* asyncExec on disposed display: no-op — never let it cancel the shared scope */
  } catch (ignored: IllegalStateException) {
    /* workbench torn down (background-thread display lookup): nothing to reflect */
  }
}

/**
 * Marshals a failure notification to the UI thread and shows it ONLY if this run is still the
 * active activation's latest generation for [key] — decided and displayed in the SAME UI turn
 * via [NotificationUtils.showOnUiThread] so no stale popup can slip through. Total (never
 * throws): the `currentDisplay` lookup can throw [IllegalStateException] from this background
 * thread once the workbench is torn down, and the launch's terminal catch calls this helper — an
 * escape here would cancel the shared scope. The in-UI show is itself wrapped in a
 * [SWTException] catch so a display disposed mid-turn cannot escape the runnable either.
 */
private fun notifyIfLatest(key: CiLintKey, myGen: Long, message: String) {
  try {
    currentDisplay.asyncExec {
      if (currentDisplay.isDisposed || !CiLintGenerationRegistry.active) return@asyncExec
      if (CiLintGenerationRegistry.isLatest(key, myGen)) {
        try {
          NotificationUtils.showOnUiThread(message)
        } catch (ignored: SWTException) {
          /* display disposed mid-turn: no-op */
        }
      }
    }
  } catch (ignored: SWTException) {
    /* disposed: notification no-op */
  } catch (ignored: IllegalStateException) {
    /* workbench torn down (background-thread display lookup): notification no-op */
  }
}
