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
    val builder = Builder(styles)
    for (i in 0 until kept) {
      val length = minOf(lengths[i], replaceFrom - starts[i])
      builder.add(StyleRun(starts[i], length, styles[styleIds[i]]))
    }
    runs.forEach { builder.add(it) }
    return builder.build()
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

  private class Builder(known: List<AnsiStyle>) {
    private val styles = ArrayList(known)
    private val ids = HashMap<AnsiStyle, Int>().also { map -> styles.forEachIndexed { i, s -> map.putIfAbsent(s, i) } }
    private val starts = ArrayList<Int>()
    private val lengths = ArrayList<Int>()
    private val styleIds = ArrayList<Int>()

    fun add(run: StyleRun) {
      if (run.length <= 0) return
      val id = ids.getOrPut(run.style) {
        styles.add(run.style)
        styles.size - 1
      }
      starts.add(run.start)
      lengths.add(run.length)
      styleIds.add(id)
    }

    fun build(): StyleRunIndex = StyleRunIndex(starts.toIntArray(), lengths.toIntArray(), styleIds.toIntArray(), styles)
  }

  companion object {
    val EMPTY = StyleRunIndex(IntArray(0), IntArray(0), IntArray(0), emptyList())

    fun of(runs: List<StyleRun>): StyleRunIndex = EMPTY.appendFrom(0, runs)
  }
}
