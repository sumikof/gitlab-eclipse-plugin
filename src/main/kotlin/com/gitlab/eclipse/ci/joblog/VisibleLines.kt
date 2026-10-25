package com.gitlab.eclipse.ci.joblog

import org.eclipse.jface.text.IDocument

/**
 * The model lines worth coloring (review I-1): widget lines [topLine]..[bottomLine] (what is on
 * screen) widened by as many lines again on each side, clamped to the widget's [lineCount],
 * mapped through [toModelLine] and merged into runs of consecutive model lines. A folded body has
 * no widget lines, so a collapsed section splits the runs and its hidden text is in none of them:
 * the work stays proportional to the screen however much is folded. A widget line that maps to
 * no model line (-1) is skipped.
 */
internal fun visibleModelLines(
  topLine: Int,
  bottomLine: Int,
  lineCount: Int,
  toModelLine: (Int) -> Int,
): List<IntRange> {
  if (lineCount <= 0) return emptyList()
  val bottom = maxOf(bottomLine, topLine)
  val screen = bottom - topLine + 1
  val first = (topLine - screen).coerceIn(0, lineCount - 1)
  val last = (bottom + screen).coerceIn(first, lineCount - 1)
  val runs = ArrayList<IntRange>()
  var start = -1
  var end = -1
  for (widgetLine in first..last) {
    val line = toModelLine(widgetLine)
    if (line < 0) continue
    if (start >= 0 && line in start..end + 1) {
      end = maxOf(end, line)
    } else {
      if (start >= 0) runs += start..end
      start = line
      end = line
    }
  }
  if (start >= 0) runs += start..end
  return runs
}

/**
 * Model offsets of each run of [lines], from the start of its first line to the end of its last
 * line, delimiter included; lines past the end of [document] are dropped, and so are empty ranges.
 */
internal fun modelLineOffsets(document: IDocument, lines: List<IntRange>): List<IntRange> {
  val lastLine = document.numberOfLines - 1
  return lines.mapNotNull { run ->
    val from = run.first.coerceAtLeast(0)
    val to = run.last.coerceAtMost(lastLine)
    if (from > to) return@mapNotNull null
    val start = document.getLineOffset(from)
    val end = document.getLineOffset(to) + document.getLineLength(to)
    if (end > start) start until end else null
  }
}
