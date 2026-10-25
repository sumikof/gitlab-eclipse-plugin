package com.gitlab.eclipse.ci.joblog

/** A fully rendered document: [text], its style [runs], the closed section [folds] and whether the start was cut. */
data class RenderResult(
  val text: String,
  val runs: StyleRunIndex,
  val folds: List<FoldRegion>,
  val omitted: Boolean,
) {
  companion object {
    val EMPTY = RenderResult("", StyleRunIndex.EMPTY, emptyList(), false)
  }
}

/**
 * Immutable parser state at the confirmed boundary. [confirmedBytes] counts from the very start of the raw log
 * (omitted bytes included); [confirmedChars] and [confirmedLines] count in the document (omitted header included).
 * [pendingRaw] holds the raw bytes after the boundary; they are re-rendered as the unfinished tail on every append.
 * [collapsedFolds] counts the folds so far that start collapsed (see [MAX_INITIALLY_COLLAPSED]).
 */
class RenderState(
  val confirmedBytes: Long,
  val confirmedChars: Int,
  val confirmedLines: Int,
  val style: AnsiStyle,
  val sections: SectionState,
  val timestamp: TimestampMode?,
  val pendingRaw: ByteArray,
  val collapsedFolds: Int = 0,
) {
  override fun equals(other: Any?): Boolean =
    other is RenderState &&
      confirmedBytes == other.confirmedBytes &&
      confirmedChars == other.confirmedChars &&
      confirmedLines == other.confirmedLines &&
      style == other.style &&
      sections == other.sections &&
      timestamp == other.timestamp &&
      collapsedFolds == other.collapsedFolds &&
      pendingRaw.contentEquals(other.pendingRaw)

  override fun hashCode(): Int =
    listOf(confirmedBytes, confirmedChars, confirmedLines, style, sections, timestamp, collapsedFolds)
      .fold(pendingRaw.contentHashCode()) { acc, v -> 31 * acc + v.hashCode() }

  override fun toString(): String =
    "RenderState(bytes=$confirmedBytes, chars=$confirmedChars, lines=$confirmedLines, pending=${pendingRaw.size})"
}

sealed interface Delta {
  /**
   * Replace the document from [replaceFrom] to its end with [text]. [runs] use absolute document offsets;
   * [newFolds] are only the regions that became final in this step. [replaceFrom] is never inside the omitted header.
   */
  data class Append(
    val replaceFrom: Int,
    val text: String,
    val runs: List<StyleRun>,
    val newFolds: List<FoldRegion>,
    val state: RenderState,
  ) : Delta

  data class Full(val result: RenderResult, val state: RenderState) : Delta
}

private const val NEWLINE: Byte = 0x0A
private const val HEADER_TEXT = OMITTED_HEADER + "\n"

/**
 * Renders [raw], the retained part of the log, from scratch. [omittedBytes] > 0 means the start was cut off and
 * the document begins with [OMITTED_HEADER].
 */
fun renderInitial(raw: ByteArray, omittedBytes: Long): Pair<RenderResult, RenderState> {
  val omitted = omittedBytes > 0
  val seed = RenderState(
    confirmedBytes = omittedBytes,
    confirmedChars = if (omitted) HEADER_TEXT.length else 0,
    confirmedLines = if (omitted) 1 else 0,
    style = AnsiStyle.DEFAULT,
    sections = SectionState(),
    timestamp = null,
    pendingRaw = ByteArray(0),
  )
  val delta = appendRender(seed, raw)
  val text = (if (omitted) HEADER_TEXT else "") + delta.text
  return RenderResult(text, StyleRunIndex.of(delta.runs), delta.newFolds, omitted) to delta.state
}

/**
 * Appends [appended] (the bytes following `confirmedBytes + pendingRaw.size`) to [state]. Lines up to the confirmed
 * boundary become final; everything after it is rendered again as the unfinished tail. For timestamped traces the
 * last line group stays unconfirmed because the next line may continue it with a `+` header.
 */
fun appendRender(state: RenderState, appended: ByteArray): Delta.Append {
  val all = state.pendingRaw + appended
  val split = splitRawLines(all)
  val mode = state.timestamp ?: detectTimestampMode(split.complete.firstOrNull() ?: split.partial)
  val bodies = split.complete.map { bodyOf(it, mode) }
  val confirmedLineCount = confirmedLineCount(bodies, mode)

  val confirmed = DocBuilder(
    state.confirmedChars,
    state.confirmedLines,
    state.style,
    state.sections,
    state.collapsedFolds,
  )
  val groups = groupLines(bodies.subList(0, confirmedLineCount))
  groups.forEach { confirmed.addLine(it, terminate = true, trackFolds = true) }

  val tailItems = bodies.subList(confirmedLineCount, bodies.size) + bodyOf(split.partial, mode)
  val tail = DocBuilder(
    state.confirmedChars + confirmed.text.length,
    state.confirmedLines + groups.size,
    confirmed.style,
    confirmed.sections,
    confirmed.collapsedFolds,
  )
  val tailGroups = groupLines(tailItems)
  tailGroups.forEachIndexed { i, line -> tail.addLine(line, terminate = i < tailGroups.size - 1, trackFolds = false) }

  val boundary = if (confirmedLineCount == 0) 0 else split.ends[confirmedLineCount - 1]
  val newState = RenderState(
    confirmedBytes = state.confirmedBytes + boundary,
    confirmedChars = state.confirmedChars + confirmed.text.length,
    confirmedLines = state.confirmedLines + groups.size,
    style = confirmed.style,
    sections = confirmed.sections,
    timestamp = if (state.timestamp != null || boundary > 0) mode else null,
    pendingRaw = all.copyOfRange(boundary, all.size),
    collapsedFolds = confirmed.collapsedFolds,
  )
  return Delta.Append(
    replaceFrom = state.confirmedChars,
    text = confirmed.text.toString() + tail.text,
    runs = confirmed.runs + tail.runs,
    newFolds = confirmed.folds,
    state = newState,
  )
}

private class RawLines(val complete: List<String>, val ends: List<Int>, val partial: String)

/** Splits at every LF; complete lines lose a trailing CR (CRLF). [RawLines.ends] are byte offsets after each LF. */
private fun splitRawLines(all: ByteArray): RawLines {
  val complete = ArrayList<String>()
  val ends = ArrayList<Int>()
  var start = 0
  for (i in all.indices) {
    if (all[i] != NEWLINE) continue
    complete.add(String(all, start, i - start, Charsets.UTF_8).removeSuffix("\r"))
    ends.add(i + 1)
    start = i + 1
  }
  return RawLines(complete, ends, String(all, start, all.size - start, Charsets.UTF_8))
}

/** A raw line reduced to its body, plus whether it continues the previous line (timestamp `+` flag). */
private fun bodyOf(line: String, mode: TimestampMode): Pair<String, Boolean> =
  if (mode == TimestampMode.TIMESTAMPED) stripTimestampHeader(line) else line to false

/** Joins each continuation line onto the line before it; the first item always starts a group. */
private fun groupLines(items: List<Pair<String, Boolean>>): List<String> {
  val groups = ArrayList<String>()
  val current = StringBuilder()
  var open = false
  items.forEachIndexed { i, (body, continues) ->
    if (i > 0 && continues) {
      current.append(body)
    } else {
      if (open) groups.add(current.toString())
      current.setLength(0)
      current.append(body)
      open = true
    }
  }
  if (open) groups.add(current.toString())
  return groups
}

/** How many leading complete lines are final: all of them, or for timestamps all but the last line group. */
private fun confirmedLineCount(bodies: List<Pair<String, Boolean>>, mode: TimestampMode): Int {
  if (mode == TimestampMode.NONE) return bodies.size
  return bodies.indices.lastOrNull { it == 0 || !bodies[it].second } ?: 0
}

/** Accumulates document text, absolute-offset runs and folds while walking lines with the parser state. */
private class DocBuilder(
  private val baseChars: Int,
  private var lineIndex: Int,
  var style: AnsiStyle,
  var sections: SectionState,
  var collapsedFolds: Int,
) {
  val text = StringBuilder()
  val runs = ArrayList<StyleRun>()
  val folds = ArrayList<FoldRegion>()

  fun addLine(line: String, terminate: Boolean, trackFolds: Boolean) {
    val info = normalizeTraceLine(line)
    val parsed = parseAnsiLine(info.text, style)
    val offset = baseChars + text.length
    parsed.runs.mapTo(runs) { it.copy(start = it.start + offset) }
    text.append(parsed.text)
    if (terminate) text.append('\n')
    style = parsed.endStyle
    if (trackFolds) closeSections(info.events)
    lineIndex++
  }

  private fun closeSections(events: List<SectionEvent>) {
    val (next, regions) = trackSection(sections, lineIndex, events)
    sections = next
    for (region in regions) {
      val collapse = region.collapsedInitially && collapsedFolds < MAX_INITIALLY_COLLAPSED
      if (collapse) collapsedFolds++
      folds.add(region.copy(collapsedInitially = collapse))
    }
  }
}
