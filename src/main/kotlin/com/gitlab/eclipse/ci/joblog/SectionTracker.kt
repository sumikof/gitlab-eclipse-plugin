package com.gitlab.eclipse.ci.joblog

/** A foldable span of document lines (0-based, inclusive); [endLine] is the line before `section_end`. */
data class FoldRegion(val startLine: Int, val endLine: Int, val collapsedInitially: Boolean)

/** Open sections, innermost last: (name, (startLine, collapsed)). */
data class SectionState(val open: List<Pair<String, Pair<Int, Boolean>>> = emptyList())

/**
 * Applies the section [events] found on document line [lineIndex]. An end closes the innermost open section of
 * the same name (dropping unterminated sections opened inside it) and yields a region only when it has at least
 * two lines. An end without a matching start is ignored.
 */
fun trackSection(
  state: SectionState,
  lineIndex: Int,
  events: List<SectionEvent>,
): Pair<SectionState, List<FoldRegion>> {
  var open = state.open
  val regions = ArrayList<FoldRegion>()
  for (event in events) {
    when (event) {
      is SectionEvent.Start -> open = open + (event.name to (lineIndex to event.collapsed))
      is SectionEvent.End -> {
        val (rest, region) = closeSection(open, event.name, lineIndex)
        open = rest
        if (region != null) regions.add(region)
      }
    }
  }
  return SectionState(open) to regions
}

private fun closeSection(
  open: List<Pair<String, Pair<Int, Boolean>>>,
  name: String,
  lineIndex: Int,
): Pair<List<Pair<String, Pair<Int, Boolean>>>, FoldRegion?> {
  val at = open.indexOfLast { it.first == name }
  if (at < 0) return open to null
  val (startLine, collapsed) = open[at].second
  val region = if (startLine < lineIndex - 1) FoldRegion(startLine, lineIndex - 1, collapsed) else null
  return open.subList(0, at) to region
}
