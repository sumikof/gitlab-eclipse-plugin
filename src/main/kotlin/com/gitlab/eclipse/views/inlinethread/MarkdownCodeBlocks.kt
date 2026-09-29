package com.gitlab.eclipse.views.inlinethread

/** One piece of a split Quick Chat answer (design §9.7). */
sealed interface Segment {
  /** Plain text between (or around) code blocks. Markdown markup is kept as-is, not rendered. */
  data class Prose(val text: String) : Segment

  /**
   * One fenced code block, with its fence lines removed.
   *
   * @param language the fence's info string's first word, or `null` when it has none.
   * @param actionable whether the popup should offer Copy/Insert for this block: it is non-blank
   *   and among the first [MarkdownCodeBlocks.MAX_ACTION_BLOCKS] non-blank code blocks of the body.
   */
  data class Code(val language: String?, val code: String, val actionable: Boolean) : Segment
}

/**
 * Splits a Quick Chat answer's Markdown body into [Segment.Prose] and [Segment.Code] pieces so the
 * popup can render each fenced code block with its own Copy/Insert affordance (design §9.7, F3).
 * [splitForDisplay] additionally caps how many of those pieces the popup renders as their own
 * control.
 *
 * Follows the CommonMark fence rules used by the reference implementation
 * (`quick_chat/utils.ts`), except the language tag is not restricted to `\w+`: a fence's info
 * string keeps its first word verbatim, so tags like `c++` or `objective-c` are recognized.
 *
 * Pure and stateless: no SWT, no I/O, safe to call off the UI thread.
 */
object MarkdownCodeBlocks {

  /**
   * Only the first this many non-blank code blocks of a body are [Segment.Code.actionable]
   * (design §9.7 resource limit; the value is a default that may be tuned during implementation).
   */
  const val MAX_ACTION_BLOCKS = 30

  /**
   * Only the first this many segments of a body are rendered as their own control by
   * [splitForDisplay]; the rest collapse into one trailing plain-text [Segment.Prose] (Codex
   * review P2: an answer made of thousands of repeated fences must not create thousands of native
   * SWT controls).
   */
  const val MAX_RENDERED_SEGMENTS = 64

  private const val MAX_FENCE_INDENT = 3

  /** [FENCE_LINE] capture group index of the info string (the fence line's text after the fence run). */
  private const val INFO_STRING_GROUP = 3

  /** A fence-open candidate: 0-3 leading spaces, then a run of 3+ backticks or tildes, then the rest of the line. */
  private val FENCE_LINE = Regex("^( {0,$MAX_FENCE_INDENT})(`{3,}|~{3,})(.*)$")

  /** A fence-close candidate: 0-3 leading spaces, a run of 3+ backticks or tildes, then only whitespace. */
  private val CLOSE_CANDIDATE = Regex("^( {0,$MAX_FENCE_INDENT})(`{3,}|~{3,})\\s*$")

  fun split(body: String): List<Segment> {
    val lines = body.replace("\r\n", "\n").split("\n")
    val segments = mutableListOf<Segment>()
    val proseLines = mutableListOf<String>()
    var nonBlankCodeBlocks = 0

    fun flushProse() {
      if (proseLines.isNotEmpty()) {
        val text = proseLines.joinToString("\n")
        if (text.isNotEmpty()) segments.add(Segment.Prose(text))
      }
      proseLines.clear()
    }

    var i = 0
    while (i < lines.size) {
      val open = FENCE_LINE.matchEntire(lines[i])
      if (open == null || !isValidFenceOpen(open)) {
        proseLines.add(lines[i])
        i++
        continue
      }

      flushProse()
      val indent = open.groupValues[1].length
      val fenceChar = open.groupValues[2][0]
      val fenceLength = open.groupValues[2].length
      val language = open.groupValues[INFO_STRING_GROUP].trim().split(WHITESPACE).first().takeIf { it.isNotEmpty() }
      i++

      val codeLines = mutableListOf<String>()
      while (i < lines.size && !isClosingFence(lines[i], fenceChar, fenceLength)) {
        codeLines.add(lines[i])
        i++
      }
      if (i < lines.size) i++ // consume the matched closing fence line; otherwise ran off the end (unclosed)

      val code = codeLines.joinToString("\n") { stripIndent(it, indent) }
      if (code.isNotBlank()) nonBlankCodeBlocks++
      val actionable = code.isNotBlank() && nonBlankCodeBlocks <= MAX_ACTION_BLOCKS
      segments.add(Segment.Code(language, code, actionable))
    }
    flushProse()

    return segments
  }

  /**
   * [split], capped for rendering (design §9.7, Codex review P2): when [split] would yield more
   * than [MAX_RENDERED_SEGMENTS] segments, the first `MAX_RENDERED_SEGMENTS - 1` are returned
   * unchanged and everything after them is coalesced into one final [Segment.Prose] that
   * re-serializes the remainder as Markdown source (a code segment becomes a fenced block again),
   * so no text is lost — only the per-block Copy/Insert affordance of the blocks past the cap.
   * [Segment.Code.actionable] is unaffected: it is already decided by [split] before this caps.
   */
  fun splitForDisplay(body: String): List<Segment> {
    val segments = split(body)
    if (segments.size <= MAX_RENDERED_SEGMENTS) return segments

    val kept = segments.take(MAX_RENDERED_SEGMENTS - 1)
    val remainder = segments.drop(MAX_RENDERED_SEGMENTS - 1).joinToString("\n") { it.toMarkdownSource() }
    return kept + Segment.Prose(remainder)
  }

  private fun Segment.toMarkdownSource(): String = when (this) {
    is Segment.Prose -> text
    is Segment.Code -> "```${language.orEmpty()}\n$code\n```"
  }

  private fun isValidFenceOpen(open: MatchResult): Boolean {
    val fenceChar = open.groupValues[2][0]
    val infoString = open.groupValues[INFO_STRING_GROUP]
    // CommonMark: a backtick fence's info string must not contain a backtick; a tilde fence has no such limit.
    return fenceChar != '`' || '`' !in infoString
  }

  private fun isClosingFence(line: String, fenceChar: Char, fenceLength: Int): Boolean {
    val close = CLOSE_CANDIDATE.matchEntire(line) ?: return false
    val run = close.groupValues[2]
    return run[0] == fenceChar && run.length >= fenceLength
  }

  private fun stripIndent(line: String, indent: Int): String {
    var strip = 0
    while (strip < indent && strip < line.length && line[strip] == ' ') strip++
    return line.substring(strip)
  }

  private val WHITESPACE = Regex("\\s+")
}
