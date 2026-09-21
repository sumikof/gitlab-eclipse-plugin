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
) {

  /** The diagnostics report as Markdown, sanitized. */
  fun report(): String = clean(DiagnosticsReport.render(collector.collect()))

  /** This plugin's retained log, sanitized; a stand-in message when nothing has been logged. */
  fun extensionLogs(): String = clean(logBuffer.getAll()).ifBlank { NO_EXTENSION_LOGS }

  /**
   * The language server's log, sanitized.
   *
   * A missing or unreadable file is **not** a failure: the server may never have started, which is
   * itself a thing the reader needs to know, and losing the whole export over it would be perverse
   * (design §14).
   */
  fun languageServerLogs(): String {
    val path = stateDirectory()?.resolve(DiagnosticsSnapshotCollector.LANGUAGE_SERVER_LOG)
    val raw = path
      ?.takeIf { runCatching { Files.isReadable(it) }.getOrDefault(false) }
      ?.let { runCatching { Files.readString(it) }.getOrNull() }
    return raw?.let(::clean)?.ifBlank { null } ?: NO_LANGUAGE_SERVER_LOGS
  }

  /** The three entries of the export, in the reference extension's order and under its names. */
  fun archiveEntries(): List<DiagnosticsEntry> = listOf(
    DiagnosticsEntry(REPORT_ENTRY, report()),
    DiagnosticsEntry(EXTENSION_LOG_ENTRY, extensionLogs()),
    DiagnosticsEntry(LANGUAGE_SERVER_LOG_ENTRY, languageServerLogs()),
  )

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
