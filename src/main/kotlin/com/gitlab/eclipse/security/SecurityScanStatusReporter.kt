package com.gitlab.eclipse.security

import com.gitlab.eclipse.lsp.diagnostics.DiagnosticFileResolver
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticUri
import org.eclipse.core.resources.IFile
import java.net.URI

/** What a scan response was decided to mean. */
sealed interface ResponseDecision {
  /** The response belongs to a connection that is gone. Nothing was consumed and nothing is shown. */
  data object Rejected : ResponseDecision

  /** [notify] is the text to show, or `null` to stay silent. [auditLine] is always recorded. */
  data class Report(val notify: String?, val auditLine: String) : ResponseDecision
}

/** Why the pending command scans of a connection were thrown away. */
enum class ScanCancelReason {
  /** The language server stopped or restarted. The user is told, because a command is waiting. */
  SERVER_STOPPED,

  /** The user switched remote scanning off. The audit records it; the user already knows. */
  SETTING_DISABLED,
}

/** One notification for the whole cancellation, and one audit line per waiter that lost a scan. */
data class CancellationReport(val notify: String?, val auditLines: List<String>)

private const val OK_STATUS = 200
private const val UNAUTHORIZED_STATUS = 401
private const val FORBIDDEN_STATUS = 403
private const val NOT_FOUND_STATUS = 404

private const val AUTH_FAILED_MESSAGE =
  "GitLab security scan failed: authentication failed. Your token may be invalid or expired. " +
    "Re-authenticate in the GitLab preferences."
private const val NOT_AVAILABLE_MESSAGE =
  "GitLab security scan failed: the real-time scan is not available for this project or namespace."
private const val UNSUPPORTED_MESSAGE =
  "GitLab security scan failed: the real-time scan is not available on this GitLab instance " +
    "(requires GitLab 17.5.0 or later)."
private const val NO_ISSUES_MESSAGE = "GitLab security scan: no issues found."

/**
 * What a command is told when its scan was thrown away by a language server restart.
 *
 * `internal` rather than file-private because [SecurityScanLauncher] hits the same situation one
 * step earlier — the epoch moves between capturing it and registering the waiter, so the request is
 * abandoned before it is sent — and the user must not be told the same thing in two different words.
 */
internal const val CANCELLED_MESSAGE =
  "GitLab security scan: the scan was cancelled because the language server restarted. " +
    "Run the scan again."

/** Placeholder for every audit field that has no value. Never an empty string, never omitted. */
private const val NONE = "-"

/** Audit outcome vocabulary (design §16.2). Shared with [SecurityScanLauncher]. */
internal const val OUTCOME_SUCCESS = "success"
internal const val OUTCOME_FAILURE = "failure"
internal const val OUTCOME_TIMEOUT = "timeout"
internal const val OUTCOME_CANCELLED = "cancelled"

/**
 * Turns a scan response into "who was waiting", "what do we tell them" and "what do we record".
 *
 * Two properties are load bearing here and are the reason this is a separate object rather than a
 * few lines in the language server client.
 *
 * **Nothing the server wrote is ever repeated.** `SecurityScanResponse.error` carries the server's
 * own exception text, which on the network and authentication paths quotes the request — a
 * `Bearer <token>` reached the Eclipse error log exactly that way in Phase 4. Every user facing
 * string here comes from the fixed table below, keyed only on the numeric status, and the audit line
 * carries the status as an integer and nothing else. A screenshot or a screen share leaks just as
 * effectively as a log file, so the notification is held to the same rule as the log.
 *
 * **A command is never suppressed.** The user pressed a button, so something visible has to come
 * back (design F6); only the save trigger deduplicates, and it deduplicates on *state* rather than
 * on time. A time based window would silently swallow the notification that says the problem came
 * back, and the user would be left believing a fix worked.
 *
 * The judgement and the state change happen in one region of [DiagnosticGenerationRegistry.lock],
 * the same monitor [CommandWaiters] uses, so "is this connection still live" and "was anybody
 * waiting" cannot disagree. Rendering, notifying and logging all happen outside it: resolving a
 * path touches the workspace, which takes locks of its own, and the fixed order for this feature is
 * outbound `Mutex` first and this monitor last.
 */
object SecurityScanStatusReporter {
  private val lock = DiagnosticGenerationRegistry.lock

  /**
   * Last failing status seen per normalised path for the save trigger only.
   *
   * The value is nullable because a response can arrive with no status at all, and "failed with an
   * unknown status" has to be distinguishable from "has not failed", which is why membership is
   * tested with `containsKey` rather than by comparing to `null`.
   */
  private val lastSaveFailure = mutableMapOf<String, Int?>()

  /** What the locked region decided, in a form that can be rendered without reading state again. */
  private data class Verdict(
    val source: SecurityScanSource,
    val succeeded: Boolean,
    val status: Int?,
    val findings: Int?,
    val notify: Boolean,
  )

  /**
   * Settles [response] against the request that was waiting for it.
   *
   * [path] is the normalised absolute path used as the waiter key; it is never put in the audit
   * line, which carries a workspace relative path or nothing at all.
   */
  fun settle(path: String, response: SecurityScanResponse, connectionEpoch: Long): ResponseDecision =
    settle(path, response, connectionEpoch, DiagnosticFileResolver::resolve)

  /**
   * As [settle], with the workspace lookup passed in.
   *
   * The seam exists so the branch that really emits a path can be tested. The workspace throws on
   * every lookup in a headless test, so without it `path=-` is the only outcome any test can ever
   * observe, and the property that matters most here — that a resolved path is the *workspace
   * relative* one and never the absolute input — would go unproven.
   */
  internal fun settle(
    path: String,
    response: SecurityScanResponse,
    connectionEpoch: Long,
    resolve: (String) -> List<IFile>,
  ): ResponseDecision {
    val verdict = synchronized(lock) {
      // A response from a connection that has already been replaced is not evidence about anything
      // current. Returning before `consumeOldest` matters as much as the answer does: consuming
      // here would cancel a scan the user started against the *new* connection.
      if (connectionEpoch != DiagnosticGenerationRegistry.currentEpoch) return ResponseDecision.Rejected
      decide(path, response, connectionEpoch)
    }
    return ResponseDecision.Report(
      notify = notification(verdict),
      auditLine = securityScanAuditLine(
        source = verdict.source,
        outcome = if (verdict.succeeded) OUTCOME_SUCCESS else OUTCOME_FAILURE,
        status = verdict.status,
        findings = verdict.findings,
        exceptionType = null,
        path = path,
        resolve = resolve,
      ),
    )
  }

  /** Call while holding [lock]. Claims the waiter and updates the save suppression state. */
  private fun decide(path: String, response: SecurityScanResponse, connectionEpoch: Long): Verdict {
    // Oldest first, because the server answers in order and the response carries nothing that
    // identifies the request it belongs to. No waiter means nobody asked, so it was a save.
    val match = CommandWaiters.consumeOldest(path, connectionEpoch)
    val source =
      if (match == WaiterMatch.COMMAND) SecurityScanSource.COMMAND else SecurityScanSource.SAVE
    val succeeded = response.status == OK_STATUS
    val findings = response.results?.size

    val notify = when {
      // Whatever the file did before, it is fine now, so the next failure is news again. Cleared
      // for both triggers: a command that succeeds proves the same thing a save would have.
      succeeded -> {
        lastSaveFailure.remove(path)
        source == SecurityScanSource.COMMAND
      }
      // The only branch a command takes on failure, and it has no condition on it.
      source == SecurityScanSource.COMMAND -> true
      // A save repeats itself on every keystroke-triggered write, so the same failure on the same
      // file stays quiet until the failure itself changes.
      else -> {
        val repeat = lastSaveFailure.containsKey(path) && lastSaveFailure[path] == response.status
        lastSaveFailure[path] = response.status
        !repeat
      }
    }
    return Verdict(source, succeeded, response.status, findings, notify)
  }

  /**
   * Drops every waiter belonging to [deadEpoch] and says what to report.
   *
   * Pass the epoch of the connection that just died, read *before* the registry was advanced;
   * passing the new one removes nothing (see [CommandWaiters.clear]).
   *
   * Only a command is told. A save has no waiter at all, so it contributes nothing here — that is a
   * property of [CommandWaiters], not a check made below.
   *
   * The composition of [dropPending] and [renderCancellation]. **Never call this while holding
   * [DiagnosticGenerationRegistry.lock]**: the rendering half resolves paths against the workspace,
   * and doing that under this monitor is what the split exists to prevent. A caller that needs the
   * drop to be atomic with a decision of its own calls the two halves itself.
   */
  fun cancelPending(deadEpoch: Long, reason: ScanCancelReason): CancellationReport =
    renderCancellation(dropPending(deadEpoch, reason), reason, DiagnosticFileResolver::resolve)

  /**
   * The half that changes state, and the only half that needs [lock].
   *
   * Split out so a caller that has to be atomic with a decision of its own — the settings
   * transition, which drops the waiters only if it is still the latest transition — can hold the
   * monitor across just this, and render afterwards. Calling the whole of [cancelPending] under the
   * monitor would put a resource tree lookup (one per dropped waiter, through the audit line) inside
   * the one monitor `DiagnosticMarkerService.applyNow` takes from inside a `WorkspaceJob`, which
   * both stalls every `publishDiagnostics` for the duration and inverts that order.
   *
   * Pass the epoch of the connection that just died, read *before* the registry was advanced;
   * passing the new one removes nothing (see [CommandWaiters.clear]).
   *
   * Returns how many waiters were dropped per path, which is all [renderCancellation] needs.
   */
  fun dropPending(deadEpoch: Long, reason: ScanCancelReason): Map<String, Int> = synchronized(lock) {
    // A restart is one of the four things that make a repeated save failure newsworthy again:
    // the server it failed against is gone, so nothing that was learned about it still holds.
    if (reason == ScanCancelReason.SERVER_STOPPED) lastSaveFailure.clear()
    CommandWaiters.clear(deadEpoch)
  }

  /**
   * The half that only reads, and that **must not run under [lock]**: it resolves every dropped
   * path against the workspace, once per dropped waiter.
   *
   * The workspace lookup is passed in rather than defaulted, for the same reason [settle] has the
   * seam — and because the caller that needs this half on its own is exactly the caller that has to
   * prove *where* it runs.
   */
  internal fun renderCancellation(
    dropped: Map<String, Int>,
    reason: ScanCancelReason,
    resolve: (String) -> List<IFile>,
  ): CancellationReport {
    val notify = when {
      dropped.isEmpty() -> null
      reason == ScanCancelReason.SERVER_STOPPED -> CANCELLED_MESSAGE
      // Switching the feature off is itself the answer; saying it twice would be noise.
      else -> null
    }
    return CancellationReport(
      notify = notify,
      // One line per *waiter*, not per file: two commands queued on the same file are two requests
      // that were thrown away, and an audit that showed one of them would undercount what happened.
      auditLines = dropped.entries.sortedBy { it.key }.flatMap { (path, count) ->
        List(count) {
          securityScanAuditLine(
            source = SecurityScanSource.COMMAND,
            outcome = OUTCOME_CANCELLED,
            status = null,
            findings = null,
            exceptionType = null,
            path = path,
            resolve = resolve,
          )
        }
      },
    )
  }

  /**
   * Forgets the save suppression state when remote scanning is switched back on.
   *
   * Without this a user who hit a failure, turned the feature off to get rid of it, and turned it
   * back on after fixing the cause would never be told about the next failure on that file.
   */
  fun onScanningReenabled() = synchronized(lock) { lastSaveFailure.clear() }

  /** Fixed client-side text for a failure. Never derived from anything the server wrote. */
  fun messageForStatus(status: Int?): String = when (status) {
    UNAUTHORIZED_STATUS -> AUTH_FAILED_MESSAGE
    FORBIDDEN_STATUS -> NOT_AVAILABLE_MESSAGE
    NOT_FOUND_STATUS -> UNSUPPORTED_MESSAGE
    else -> "GitLab security scan failed (status ${status ?: NONE}). See the Error Log for details."
  }

  private fun notification(verdict: Verdict): String? {
    if (!verdict.notify) return null
    if (!verdict.succeeded) return messageForStatus(verdict.status)
    // A response with no `results` at all is reported as a clean scan: the server said 200, and
    // there is nothing to point the user at. The audit line still records that the list was absent.
    val count = verdict.findings ?: 0
    if (count == 0) return NO_ISSUES_MESSAGE
    return "GitLab security scan: $count issue(s) found. See the Problems view."
  }

  fun resetForTest() = synchronized(lock) { lastSaveFailure.clear() }
}

/**
 * The one place an audit line is built (design §16.2), for every outcome and every caller.
 *
 * Kept as a single function on purpose: the secrecy rules are per-field, so a second builder
 * somewhere else is how a raw path or an error body eventually gets out. [SecurityScanLauncher]
 * reports the outcomes this object never sees — a request that timed out or never left — through
 * here rather than through prose of its own.
 *
 * Every absent value is written as `-`; no field is ever dropped, so the shape of the line does not
 * depend on what happened.
 */
@Suppress("LongParameterList")
internal fun securityScanAuditLine(
  source: SecurityScanSource,
  outcome: String,
  status: Int?,
  findings: Int?,
  exceptionType: String?,
  path: String,
  resolve: (String) -> List<IFile> = DiagnosticFileResolver::resolve,
): String = "securityScan source=${source.wireValue} outcome=$outcome " +
  "httpStatus=${status ?: NONE} findings=${findings ?: NONE} " +
  "exceptionType=${exceptionType ?: NONE} path=${workspaceRelative(path, resolve)}"

/**
 * Turns the waiter key into something safe to write down.
 *
 * The key is an absolute path on the user's machine, which the error log must never carry
 * (design §16.1), so it is exchanged for the workspace relative path of the file it resolves to.
 * A path that resolves to nothing — a file outside the workspace, or any lookup at all while the
 * workspace is closed — is recorded as absent rather than falling back to the absolute form.
 */
private fun workspaceRelative(path: String, resolve: (String) -> List<IFile>): String =
  runCatching { resolve(path).firstOrNull()?.fullPath?.toString() }.getOrNull() ?: NONE

/**
 * Two or more characters before the colon, so a Windows drive letter is not mistaken for a scheme.
 * `C:/src/a.kt` has to be read as a path; no real URI scheme is a single character.
 */
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]+:")

/**
 * Normalises whatever the language server called the scanned file into the waiter key.
 *
 * The server echoes back its own spelling of the file and nothing pins down what that is: the
 * request carries `file:/abs/path`, but the server is built on `vscode-uri` and may answer with
 * `file:///abs/path`, and nothing rules out a bare OS path either. Unconditionally prefixing
 * `file:` — which is what the naive version does — turns `file:///a.kt` into the opaque URI
 * `file:file:///a.kt`, whose path is null, and leaves a Windows path unparseable; both then fall
 * back to a key that matches no waiter, so the command that is waiting is never answered.
 *
 * So: something that already has a scheme is normalised as it stands, and a bare path is made into
 * a `file:` URI first, with separators and the leading slash fixed up so a Windows path survives.
 *
 * A value that still cannot be normalised is used as-is. It will not match a waiter, which is the
 * honest outcome — the deadline then reports the command as unanswered — and it cannot leak,
 * because the audit line never carries this value.
 */
internal fun securityScanPathKey(filePath: String): String {
  val candidate =
    if (URI_SCHEME.containsMatchIn(filePath)) filePath else fileUriFor(filePath)
  return DiagnosticUri.normalize(candidate) ?: filePath
}

/**
 * Builds a `file:` URI for a bare OS path.
 *
 * Deliberately **not** string concatenation. A path is not a URI: a space makes `URI()` throw
 * outright, and a `#` is read as a fragment separator, so `/w/a#b.kt` would silently become the key
 * `/w/a`. Either way the key stops matching the one the request registered and the waiting command
 * is only ever released by its sixty second deadline.
 *
 * This is `java.io.File.toURI()`'s own algorithm — slashify, then the multi-argument [URI]
 * constructor, which quotes whatever the path syntax does not allow — with one part left out:
 * `File.toURI()` first calls `getAbsoluteFile()`, which resolves against the *running* JVM's
 * notion of an absolute path. On a POSIX JVM a Windows path is therefore treated as relative and
 * prefixed with the process working directory, which both destroys the key and writes the working
 * directory into it. The language server decides the spelling, not the JVM, so the platform must
 * not take part in this.
 *
 * The extra `//` on a path that already starts with one is what keeps a UNC host out of the URI's
 * authority; without it `\\host\share\f.kt` becomes `file://host/share/f.kt`, whose path is merely
 * `/share/f.kt`.
 */
private fun fileUriFor(rawPath: String): String {
  val slashed = rawPath.replace('\\', '/')
  val absolute = if (slashed.startsWith("/")) slashed else "/$slashed"
  val path = if (absolute.startsWith("//")) "//$absolute" else absolute
  return runCatching { URI("file", null, path, null).toASCIIString() }.getOrNull() ?: rawPath
}
