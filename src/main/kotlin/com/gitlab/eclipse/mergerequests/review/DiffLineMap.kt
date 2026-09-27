package com.gitlab.eclipse.mergerequests.review

/** Classification of one line in a diff's new (post-change) file. */
sealed interface NewLineKind {
  /** The line was added or modified by the diff; it has no corresponding old-file line. */
  object Added : NewLineKind

  /** The line is unchanged from the old file, where it was at [oldLine] (1-based). */
  data class Unchanged(val oldLine: Int) : NewLineKind
}

/**
 * Maps 1-based new-file line numbers to their [NewLineKind], derived from one file's
 * unified-diff hunks (GitLab REST `diffs[].diff`). See [classify].
 */
sealed interface DiffLineMap {
  /**
   * No line mapping is available: the diff is too large or was collapsed, contains no hunk
   * header at all (e.g. a binary-file notice), or contains a hunk body line this parser cannot
   * interpret (fail-safe: an unparseable line invalidates the whole file rather than silently
   * shifting later lines).
   */
  object Unavailable : DiffLineMap

  /** A pure rename with identical content: every new-file line maps 1:1 to the same old-file line. */
  object Identity : DiffLineMap

  /** Hunks parsed from a unified diff, in file order. */
  class Parsed internal constructor(internal val hunks: List<Hunk>) : DiffLineMap

  companion object {
    // Same pattern as the reference implementation, src/desktop/git/diff_line_count.ts:17.
    private val HUNK_HEADER = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /**
     * Parses one file's unified-diff body (REST `diffs[].diff`). Non-empty input only; the
     * empty-diff cases are decided by T3a's `classifyEmptyDiff`.
     */
    fun parse(diff: String): DiffLineMap {
      val lines = diff.split("\n")
      val headerLineIndices = lines.indices.filter { isHunkHeaderLine(lines[it]) }
      if (headerLineIndices.isEmpty()) return Unavailable
      val hunks = headerLineIndices.mapIndexed { i, headerIndex ->
        val bodyEnd = headerLineIndices.getOrElse(i + 1) { lines.size }
        parseHunk(lines[headerIndex], lines.subList(headerIndex + 1, bodyEnd)) ?: return Unavailable
      }
      return Parsed(hunks)
    }

    private fun isHunkHeaderLine(line: String) = HUNK_HEADER.find(line)?.range?.first == 0

    /** Returns null if [body] contains a line this parser cannot interpret. */
    private fun parseHunk(header: String, body: List<String>): Hunk? {
      val match = requireNotNull(HUNK_HEADER.find(header)) { "not a hunk header: $header" }
      val oldStart = match.groupValues[1].toInt()
      val newStart = match.groupValues[2].toInt()
      var oldLine = oldStart
      var oldLineCount = 0
      val entries = mutableListOf<NewLineKind>()
      for (line in body) {
        if (line.isEmpty() || line.startsWith("\\")) continue // "\ No newline at end of file"
        when (line[0]) {
          '+' -> entries += NewLineKind.Added
          '-' -> {
            oldLine++
            oldLineCount++
          }
          ' ' -> {
            entries += NewLineKind.Unchanged(oldLine)
            oldLine++
            oldLineCount++
          }
          else -> return null // unrecognized prefix: fail-safe, never silently miscount
        }
      }
      return Hunk(oldStart, newStart, entries, oldLineCount)
    }
  }
}

/**
 * One parsed hunk: [entries] are this hunk's new-file lines in order, starting at [newStart].
 * [oldLineCount] is the number of old-file lines the hunk consumes (unchanged + removed).
 *
 * Zero-count sides follow git's unified-diff convention: a `,0` range's header start is the line
 * BEFORE the (empty) range, not the first line in it (e.g. `@@ -5,3 +4,0 @@` deletes old lines
 * 5-7 immediately after new line 4; `@@ -0,0 +1,3 @@` inserts at the very start of a new file).
 * So when [entries] is empty, the hunk's first new line is one past [headerNewStart]; when
 * [oldLineCount] is zero, the hunk's last old line is exactly [headerOldStart] (not one before
 * it).
 */
internal class Hunk(headerOldStart: Int, headerNewStart: Int, val entries: List<NewLineKind>, oldLineCount: Int) {
  val newStart: Int = if (entries.isEmpty()) headerNewStart + 1 else headerNewStart
  val newEnd: Int = newStart + entries.size - 1
  private val oldStart: Int = if (oldLineCount == 0) headerOldStart + 1 else headerOldStart
  private val oldEnd: Int = oldStart + oldLineCount - 1

  /** The cumulative new-vs-old line-number offset in effect immediately after this hunk. */
  val delta: Int = newEnd - oldEnd
}

/**
 * Classifies [oneBasedNewLine] (a 1-based new-file line number) using this [DiffLineMap].
 * [DiffLineMap.Unavailable] yields null. [DiffLineMap.Identity] yields
 * `Unchanged(oneBasedNewLine)`. [DiffLineMap.Parsed] looks up the hunk containing the line; for
 * lines outside any hunk, it falls back to the cumulative new-vs-old offset of the last hunk at
 * or before the line (zero before the first hunk). Throws [IllegalArgumentException] if
 * [oneBasedNewLine] is less than 1.
 */
fun DiffLineMap.classify(oneBasedNewLine: Int): NewLineKind? {
  require(oneBasedNewLine >= 1) { "oneBasedNewLine must be >= 1, was $oneBasedNewLine" }
  return when (this) {
    DiffLineMap.Unavailable -> null
    DiffLineMap.Identity -> NewLineKind.Unchanged(oneBasedNewLine)
    is DiffLineMap.Parsed -> classifyParsed(oneBasedNewLine)
  }
}

private fun DiffLineMap.Parsed.classifyParsed(oneBasedNewLine: Int): NewLineKind {
  var offset = 0
  for (hunk in hunks) {
    if (oneBasedNewLine < hunk.newStart) return NewLineKind.Unchanged(oneBasedNewLine - offset)
    if (oneBasedNewLine <= hunk.newEnd) return hunk.entries[oneBasedNewLine - hunk.newStart]
    offset = hunk.delta
  }
  return NewLineKind.Unchanged(oneBasedNewLine - offset)
}
