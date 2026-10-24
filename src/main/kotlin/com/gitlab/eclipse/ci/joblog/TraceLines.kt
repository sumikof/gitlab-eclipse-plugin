package com.gitlab.eclipse.ci.joblog

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** A GitLab `section_start` / `section_end` marker found on a trace line. */
sealed interface SectionEvent {
  data class Start(val name: String, val collapsed: Boolean) : SectionEvent

  data class End(val name: String) : SectionEvent
}

/** A line ready for display: [text] still carries ANSI escapes; [events] are the section markers it held. */
data class RawLineInfo(val text: String, val events: List<SectionEvent>)

private val sectionMarker = Regex("""section_(start|end):\d+:([^\[\r\s]+)(?:\[([^\]\r]*)])?""")

/** Matches C0 controls except TAB (LF and CR are consumed earlier), plus DEL. ESC is kept for the ANSI parser. */
private val residualControl = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001A\u001C-\u001F\u007F]")

/**
 * Normalizes one trace line (no `\n`): detects section markers before the last `\r`, keeps only the text after
 * the last `\r` (progress overwrite), removes marker text, then removes C0 controls (except TAB) and DEL.
 * ANSI escapes are left for [parseAnsiLine].
 */
fun normalizeTraceLine(line: String): RawLineInfo {
  val body = line.removeSuffix("\r")
  val lastCr = body.lastIndexOf('\r')
  val searchable = if (lastCr == -1) body else body.substring(0, lastCr)
  val events = sectionMarker.findAll(searchable).map { toEvent(it) }.toList()
  val visible = if (lastCr == -1) body else body.substring(lastCr + 1)
  val text = residualControl.replace(sectionMarker.replace(visible, ""), "")
  return RawLineInfo(text, events)
}

private fun toEvent(match: MatchResult): SectionEvent {
  val name = match.groupValues[2]
  if (match.groupValues[1] == "end") return SectionEvent.End(name)
  val options = match.groupValues[OPTION_GROUP].split(',').map { it.trim() }
  return SectionEvent.Start(name, "collapsed=true" in options)
}

enum class TimestampMode { NONE, TIMESTAMPED }

private const val HEADER_EXTRA = 5
private const val OPTION_GROUP = 3

/** TIMESTAMPED when the first token (up to the first space) of [firstLine] is an ISO-8601 date-time. */
fun detectTimestampMode(firstLine: String): TimestampMode {
  val space = firstLine.indexOf(' ')
  if (space <= 0) return TimestampMode.NONE
  val token = firstLine.substring(0, space)
  val parsed = try {
    Instant.parse(token)
    true
  } catch (_: DateTimeParseException) {
    try {
      OffsetDateTime.parse(token)
      true
    } catch (_: DateTimeParseException) {
      false
    }
  }
  return if (parsed) TimestampMode.TIMESTAMPED else TimestampMode.NONE
}

/**
 * For a TIMESTAMPED trace: drops the `<timestamp> <id><stream><flag>` header (length timestamp + 5) and reports
 * whether the line continues the previous one (header ends with `+`).
 */
fun stripTimestampHeader(line: String): Pair<String, Boolean> {
  val space = line.indexOf(' ')
  val headerLength = (if (space < 0) line.length else space) + HEADER_EXTRA
  if (line.length < headerLength) return "" to false
  return line.substring(headerLength) to (line[headerLength - 1] == '+')
}
