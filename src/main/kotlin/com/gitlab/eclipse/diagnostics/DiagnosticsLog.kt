package com.gitlab.eclipse.diagnostics

import org.eclipse.core.runtime.ILog

/**
 * The plugin's in-memory log, and the tap that fills it (design §7).
 *
 * A plain object rather than a Koin binding, matching how this codebase holds the other pieces of
 * cross-cutting state it installs at startup (`JobLogGenerationRegistry`,
 * `DiagnosticGenerationRegistry`). The tap has to be reachable from bundle start, before the
 * container is necessarily useful, and from command handlers afterwards.
 */
object DiagnosticsLog {

  /** Everything this plugin has logged, up to the ring buffer's cap. */
  val buffer: LogRingBuffer = LogRingBuffer()

  @Volatile
  private var tap: PluginLogTap? = null

  /**
   * Starts copying [log] into [buffer]. Idempotent: a second call replaces the first tap rather
   * than adding another, so a stop/start cycle in one class loader cannot double every line.
   *
   * @param knownSecrets the credentials live right now, redacted out of each line as it is captured
   *   (design §10.1). Evaluated per log line, so a token that is later rotated was already removed
   *   from the lines recorded while it was current.
   */
  fun install(log: ILog, knownSecrets: () -> Collection<String>) {
    uninstall(log)
    tap = PluginLogTap(buffer, knownSecrets = knownSecrets).also(log::addLogListener)
  }

  /** Stops copying. Safe to call when nothing is installed. */
  fun uninstall(log: ILog) {
    tap?.let(log::removeLogListener)
    tap = null
  }
}
