package com.gitlab.eclipse.ci.joblog

/**
 * Immutable, offset-ordered style runs stored in parallel arrays so that [runsIn] can binary search.
 * Runs must be sorted by start and must not overlap.
 */
class StyleRunIndex private constructor(
  private val starts: IntArray,
  private val lengths: IntArray,
  private val styleIds: IntArray,
  private val styles: List<AnsiStyle>,
) {
  val size: Int get() = starts.size

  /** Runs overlapping `[offset, offset + length)`, clipped to that range, in order. */
  fun runsIn(offset: Int, length: Int): List<StyleRun> {
    if (length <= 0 || size == 0) return emptyList()
    val end = offset + length
    val found = ArrayList<StyleRun>()
    var i = firstEndingAfter(offset)
    while (i < size && starts[i] < end) {
      val from = maxOf(starts[i], offset)
      val to = minOf(starts[i] + lengths[i], end)
      if (to > from) found.add(StyleRun(from, to - from, styles[styleIds[i]]))
      i++
    }
    return found
  }

  /**
   * Drops everything from [replaceFrom] on (a run straddling it is cut off there) and appends [runs], whose
   * offsets are absolute. Returns a new index; this one is unchanged.
   */
  fun appendFrom(replaceFrom: Int, runs: List<StyleRun>): StyleRunIndex {
    val kept = countStartingBefore(replaceFrom)
    val added = runs.count { it.length > 0 }
    val newStarts = IntArray(kept + added)
    val newLengths = IntArray(kept + added)
    val newIds = IntArray(kept + added)
    starts.copyInto(newStarts, 0, 0, kept)
    lengths.copyInto(newLengths, 0, 0, kept)
    styleIds.copyInto(newIds, 0, 0, kept)
    // Runs are sorted and do not overlap, so only the last kept run can straddle replaceFrom.
    if (kept > 0) newLengths[kept - 1] = minOf(lengths[kept - 1], replaceFrom - starts[kept - 1])

    val table = StyleTable(styles)
    var next = kept
    for (run in runs) {
      if (run.length <= 0) continue
      newStarts[next] = run.start
      newLengths[next] = run.length
      newIds[next] = table.idOf(run.style)
      next++
    }
    return StyleRunIndex(newStarts, newLengths, newIds, table.styles)
  }

  fun toList(): List<StyleRun> = (0 until size).map { StyleRun(starts[it], lengths[it], styles[styleIds[it]]) }

  override fun equals(other: Any?): Boolean = other is StyleRunIndex && toList() == other.toList()

  override fun hashCode(): Int = toList().hashCode()

  override fun toString(): String = "StyleRunIndex(${toList()})"

  /** Index of the first run that ends after [offset] (runs are sorted, so ends are sorted too). */
  private fun firstEndingAfter(offset: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (starts[mid] + lengths[mid] > offset) high = mid else low = mid + 1
    }
    return low
  }

  /** Number of runs that start before [offset]. */
  private fun countStartingBefore(offset: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (starts[mid] >= offset) high = mid else low = mid + 1
    }
    return low
  }

  /** The known styles plus any new ones (a copy of the small style list; the run arrays are what is big). */
  private class StyleTable(known: List<AnsiStyle>) {
    val styles = ArrayList(known)
    private val ids = HashMap<AnsiStyle, Int>().also { map -> known.forEachIndexed { i, s -> map.putIfAbsent(s, i) } }

    fun idOf(style: AnsiStyle): Int = ids.getOrPut(style) {
      styles.add(style)
      styles.size - 1
    }
  }

  companion object {
    val EMPTY = StyleRunIndex(IntArray(0), IntArray(0), IntArray(0), emptyList())

    fun of(runs: List<StyleRun>): StyleRunIndex = EMPTY.appendFrom(0, runs)
  }
}
