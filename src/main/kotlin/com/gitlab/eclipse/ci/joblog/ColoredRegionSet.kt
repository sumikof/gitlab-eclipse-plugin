package com.gitlab.eclipse.ci.joblog

/**
 * The document ranges that already carry their colors, as sorted, non-touching spans. Mutable and meant for the
 * UI thread only (design R2-1).
 */
class ColoredRegionSet {
  private class Span(val start: Int, val end: Int)

  private var spans: List<Span> = emptyList()

  /** Marks `[offset, offset + length)` as colored, merging with touching or overlapping spans. */
  fun add(offset: Int, length: Int) {
    if (length <= 0) return
    var start = offset
    var end = offset + length
    val rest = ArrayList<Span>()
    for (span in spans) {
      if (span.end < start || span.start > end) {
        rest.add(span)
      } else {
        start = minOf(start, span.start)
        end = maxOf(end, span.end)
      }
    }
    rest.add(Span(start, end))
    spans = rest.sortedBy { it.start }
  }

  /** Marks `[offset, offset + length)` as not colored. */
  fun subtract(offset: Int, length: Int) {
    if (length <= 0) return
    val end = offset + length
    val rest = ArrayList<Span>()
    for (span in spans) {
      if (span.end <= offset || span.start >= end) {
        rest.add(span)
        continue
      }
      if (span.start < offset) rest.add(Span(span.start, offset))
      if (span.end > end) rest.add(Span(end, span.end))
    }
    spans = rest
  }

  fun clear() {
    spans = emptyList()
  }

  /** The parts of `[offset, offset + length)` that are not colored yet, as inclusive ranges in order. */
  fun missingIn(offset: Int, length: Int): List<IntRange> {
    if (length <= 0) return emptyList()
    val end = offset + length
    val missing = ArrayList<IntRange>()
    var cursor = offset
    for (span in spans.filter { it.end > offset && it.start < end }) {
      if (span.start > cursor) missing.add(cursor until span.start)
      cursor = maxOf(cursor, span.end)
    }
    if (cursor < end) missing.add(cursor until end)
    return missing
  }
}
