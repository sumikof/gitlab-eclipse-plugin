package com.gitlab.eclipse.diagnostics

import org.eclipse.core.runtime.ILogListener
import org.eclipse.core.runtime.IStatus
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Copies this plugin's own log into [LogRingBuffer], so a diagnostics export has something to
 * attach (design §9.1).
 *
 * Attaches as an [ILogListener] rather than changing how anything logs: every existing
 * `logger<T>().info(...)` call keeps working untouched, and code written later is captured without
 * having to know this exists (design §18).
 *
 * **Two rules govern everything here, and both are about not making things worse.**
 *
 * 1. *Nothing in this class may log.* A log call from inside a log listener re-enters the listener,
 *    which logs, which re-enters — a stack overflow during startup. There is a [reentrant] guard as
 *    well, because a buffer implementation could log on its own, but the first defence is that this
 *    code simply never calls a logger, not on any path, including its failure paths (design §13).
 * 2. *Nothing in this class may throw.* The listener runs inside Eclipse's log delivery. An
 *    exception escaping here would take out the delivery of the very message being logged, so
 *    collection failure must never cost the original log line (design §14, N3).
 *
 * The formatted status is split into **physical lines** before it is handed over, so the buffer's
 * 5000-line cap means 5000 actual lines. Appending a whole stack trace as one entry would let a
 * single exception consume tens of lines of budget while counting as one (design §9.1, A17).
 */
class PluginLogTap(
  private val buffer: LogRingBuffer,
  private val now: () -> Instant = Instant::now,
  private val zone: ZoneId = ZoneId.systemDefault(),
  private val knownSecrets: () -> Collection<String> = { emptyList() },
) : ILogListener {

  // Per-thread: delivery is per-thread, and a guard shared across threads would drop a legitimate
  // line logged concurrently by another thread.
  private val reentrant = ThreadLocal.withInitial { false }

  override fun logging(status: IStatus, plugin: String?) {
    if (reentrant.get()) return
    reentrant.set(true)
    try {
      val secrets = currentSecrets()
      format(status).forEach { line -> buffer.append(scrub(line, secrets)) }
    } catch (_: Exception) {
      // Deliberately silent, and deliberately broad. See rule 2 in the class comment: there is no
      // way to report this that does not risk the recursion rule 1 exists to prevent.
    } finally {
      reentrant.set(false)
    }
  }

  /**
   * Replaces the credentials live *at the moment the line was logged*, before the line is retained.
   *
   * This is the answer to a leak the export-time sanitizer cannot close: by the time the user
   * exports, an OAuth token may have been refreshed or a PAT replaced, so the value passed to
   * [DiagnosticsSanitizer] no longer matches the **older** token still sitting in lines captured
   * before the change. Scrubbing here sidesteps that entirely — whatever token was current when a
   * line was written is the one that could appear in it.
   *
   * Only literal replacement happens here, never the regex passes: this runs on the logging path,
   * and [String.replace] on a handful of values is cheap enough to sit there. The shape-based
   * patterns run once at export instead.
   *
   * **This covers the ring buffer only.** `language_server.log` is written by the language server
   * process through log4j, so the plugin cannot scrub it as it is produced; that file is protected
   * by the export-time patterns and the then-current token alone. The residual risk is recorded in
   * the pull request.
   */
  private fun scrub(line: String, secrets: Collection<String>): String =
    secrets.fold(line) { acc, secret -> acc.replace(secret, REDACTED) }

  /** Never lets a failing secrets lookup cost the log line (rule 2 in the class comment). */
  private fun currentSecrets(): Collection<String> =
    runCatching { knownSecrets().filter { it.isNotBlank() } }.getOrDefault(emptyList())

  /**
   * One status as the lines it occupies: a header line, then the exception's stack trace indented
   * by [PADDING] spaces, matching the reference extension's `multilineLog`.
   */
  private fun format(status: IStatus): List<String> {
    val timestamp = TIMESTAMP.format(now().atZone(zone))
    val header = "$timestamp [${severityOf(status)}]: ${status.message.orEmpty()}"
    val body = status.exception?.let { stackTraceOf(it) }.orEmpty()
    return (header + body).normalizeNewlines().split('\n').mapIndexed { index, line ->
      // The first physical line already carries the prefix; continuation lines are indented so the
      // block reads as one entry in a flat log.
      if (index == 0) line else " ".repeat(PADDING) + line
    }
  }

  private fun stackTraceOf(throwable: Throwable): String {
    val writer = StringWriter()
    PrintWriter(writer).use(throwable::printStackTrace)
    return "\n" + writer.toString().trimEnd()
  }

  private fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

  /**
   * Eclipse severities in the reference extension's vocabulary. `IStatus.OK` reads as `info`:
   * Eclipse uses OK for plain informational entries, and a diagnostics reader looking for the
   * reference's four levels should not meet a fifth.
   */
  private fun severityOf(status: IStatus): String = when (status.severity) {
    IStatus.ERROR -> "error"
    IStatus.WARNING -> "warning"
    IStatus.CANCEL -> "cancel"
    else -> "info"
  }

  private companion object {
    /** Matches the reference extension's `log.ts` padding of continuation lines. */
    const val PADDING = 4

    /** What a captured credential is replaced with; the export-time sanitizer uses the same text. */
    const val REDACTED = "[REDACTED_TOKEN]"

    // No withZone(): the caller's zone is applied by atZone() before formatting, and a zone set
    // here would silently override it.
    val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
  }
}
