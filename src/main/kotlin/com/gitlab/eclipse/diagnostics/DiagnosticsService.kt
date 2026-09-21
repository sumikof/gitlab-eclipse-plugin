package com.gitlab.eclipse.diagnostics

import java.nio.file.Files
import java.nio.file.Path

/**
 * Produces the three artefacts the D10 commands hand to the user: the report, the plugin log, and
 * the archive containing both plus the language server's log (design §9.2–§9.4).
 *
 * Everything leaving here has been through [DiagnosticsSanitizer]. That is the single choke point;
 * no caller is trusted to remember, and there is no method that returns unsanitized text.
 *
 * Pure of UI and of threading policy: it does file reads and string work, and says nothing about
 * where it runs. The export handler is what puts it on a background job (N5).
 */
class DiagnosticsService(
  private val collector: DiagnosticsSnapshotCollector = DiagnosticsSnapshotCollector(),
  private val sanitizer: DiagnosticsSanitizer = DiagnosticsSanitizer(),
  private val logBuffer: LogRingBuffer = DiagnosticsLog.buffer,
  private val stateDirectory: () -> Path? = DiagnosticsSnapshotCollector::pluginStateDirectory,
  private val knownSecrets: () -> Collection<String> = ::configuredSecrets,
  /**
   * Publishes what is in secure storage before sanitizing, so literal redaction still has the
   * token to match when the language server never started and therefore never built a configuration
   * payload to publish one (the only other publisher). Called from the four public methods only,
   * each of which is a user-initiated command — never from the log-capture path.
   */
  private val publishStoredSecrets: () -> Unit = StoredSecrets::publish,
) {

  /** The diagnostics report as Markdown, sanitized. */
  fun report(): String {
    runCatching(publishStoredSecrets)
    return renderReport()
  }

  /** This plugin's retained log, sanitized; a stand-in message when nothing has been logged. */
  fun extensionLogs(): String {
    runCatching(publishStoredSecrets)
    return renderExtensionLogs()
  }

  /**
   * The language server's log, sanitized.
   *
   * A missing or unreadable file is **not** a failure: the server may never have started, which is
   * itself a thing the reader needs to know, and losing the whole export over it would be perverse
   * (design §14).
   */
  fun languageServerLogs(): String {
    runCatching(publishStoredSecrets)
    return renderLanguageServerLogs()
  }

  private fun renderReport(): String = clean(DiagnosticsReport.render(collector.collect()))

  private fun renderExtensionLogs(): String = clean(logBuffer.getAll()).ifBlank { NO_EXTENSION_LOGS }

  private fun renderLanguageServerLogs(): String {
    val path = stateDirectory()?.resolve(DiagnosticsSnapshotCollector.LANGUAGE_SERVER_LOG)
      ?.takeIf { runCatching { Files.isReadable(it) }.getOrDefault(false) }
      ?: return NO_LANGUAGE_SERVER_LOGS
    // String(bytes, UTF_8) REPLACES malformed bytes; Files.readString would throw on the first one.
    // log4j writes this file with no charset set, from lines the server emitted in the platform
    // native encoding, so one mismatched byte in up to 20 MB must not discard the whole log —
    // still less discard it behind a message that reads as "the server never ran".
    val raw = runCatching { String(Files.readAllBytes(path), Charsets.UTF_8) }.getOrNull()
      ?: return UNREADABLE_LANGUAGE_SERVER_LOG
    return clean(raw).ifBlank { NO_LANGUAGE_SERVER_LOGS }
  }

  /** The three entries of the export, in the reference extension's order and under its names. */
  fun archiveEntries(): List<DiagnosticsEntry> {
    // Published once for the whole export rather than once per entry: the OAuth read logs, and
    // three reads would put three lines into the very buffer being exported.
    runCatching(publishStoredSecrets)
    return listOf(
      DiagnosticsEntry(REPORT_ENTRY, renderReport()),
      DiagnosticsEntry(EXTENSION_LOG_ENTRY, renderExtensionLogs()),
      DiagnosticsEntry(LANGUAGE_SERVER_LOG_ENTRY, renderLanguageServerLogs()),
    )
  }

  /** The export archive as bytes. */
  fun buildArchive(): ByteArray = DiagnosticsArchive.build(archiveEntries())

  /**
   * Writes [text] into the plugin's state directory under [fileName] and returns the path, so a
   * command can open it in an editor.
   *
   * The state directory is where `language_server.log` already lives, which keeps everything this
   * feature produces in one place the user can be pointed at.
   */
  fun materialize(fileName: String, text: String): Path {
    val directory = requireNotNull(stateDirectory()) { "The plugin state directory is unavailable." }
    Files.createDirectories(directory)
    return Files.writeString(directory.resolve(fileName), text)
  }

  private fun clean(text: String): String =
    sanitizer.sanitize(text, runCatching(knownSecrets).getOrDefault(emptyList()))

  companion object {
    const val REPORT_ENTRY = "diagnostics.md"
    const val EXTENSION_LOG_ENTRY = "extension-logs.txt"
    const val LANGUAGE_SERVER_LOG_ENTRY = "language-server-logs.txt"

    const val REPORT_FILE = "GitLab Diagnostics.md"
    const val EXTENSION_LOG_FILE = "gitlab-plugin-logs.txt"

    const val NO_EXTENSION_LOGS = "No extension logs available."
    const val NO_LANGUAGE_SERVER_LOGS = "No language server logs available."

    /** Distinct from [NO_LANGUAGE_SERVER_LOGS]: the file is there, but could not be read. */
    const val UNREADABLE_LANGUAGE_SERVER_LOG =
      "The language server log exists but could not be read."
  }
}

/**
 * The credentials to redact literally (design §10.1).
 *
 * Reads the published cache rather than asking the token provider: on the OAuth path that ask
 * refreshes over the network, logs and can notify (see [DiagnosticsSecrets]), none of which belongs
 * behind a diagnostics command either.
 */
internal fun configuredSecrets(): Collection<String> = DiagnosticsSecrets.current()
