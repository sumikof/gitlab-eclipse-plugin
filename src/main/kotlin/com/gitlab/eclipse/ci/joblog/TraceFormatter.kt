package com.gitlab.eclipse.ci.joblog

/**
 * Matches an ANSI CSI escape sequence: ESC `[`, parameter bytes (0x30-0x3F), intermediate bytes
 * (0x20-0x2F), one final byte (0x40-0x7E). Covers SGR color codes (e.g. `ESC[32m`) and
 * erase-in-line (`ESC[0K`, `ESC[K`).
 */
private val ansiCsiRegex = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

/** Matches a GitLab `section_start`/`section_end` marker token. */
private val sectionMarkerRegex = Regex("""section_(?:start|end):\d+:[A-Za-z0-9_.\-]*""")

/**
 * Matches residual C0/DEL control characters, excluding TAB (kept), LF (already consumed by line
 * splitting) and CR (handled separately as an overwrite marker before this runs).
 */
private val residualControlCharRegex = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

/**
 * Converts a raw GitLab job-trace string into readable plain text: resolves carriage-return
 * overwrite, strips ANSI CSI escapes and GitLab section markers, and normalizes newlines.
 *
 * This produces plain text only - no ANSI color rendering, no folding, no timestamp parsing.
 */
fun stripTraceFormatting(raw: String): String {
  if (raw.isEmpty()) return ""

  val normalized = raw.replace("\r\n", "\n")

  return normalized
    .split('\n')
    .joinToString("\n") { line -> processLine(line) }
}

private fun processLine(line: String): String {
  val afterOverwrite = resolveCarriageReturnOverwrite(line)
  val afterAnsi = ansiCsiRegex.replace(afterOverwrite, "")
  val afterSectionMarkers = sectionMarkerRegex.replace(afterAnsi, "")
  return residualControlCharRegex.replace(afterSectionMarkers, "")
}

private fun resolveCarriageReturnOverwrite(line: String): String {
  val lastCr = line.lastIndexOf('\r')
  return if (lastCr == -1) line else line.substring(lastCr + 1)
}
