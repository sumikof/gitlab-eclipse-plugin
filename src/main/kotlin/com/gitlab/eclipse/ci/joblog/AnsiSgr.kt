@file:Suppress("MagicNumber")

package com.gitlab.eclipse.ci.joblog

/** A terminal color: one of the 16 named colors (0..15) or a direct RGB value. */
sealed interface AnsiColor {
  /** [index] 0..7 are the normal colors and 8..15 the bright ones. */
  data class Named(val index: Int) : AnsiColor

  data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor
}

/** Text attributes set by SGR escapes; null colors mean "editor default". */
data class AnsiStyle(
  val fg: AnsiColor? = null,
  val bg: AnsiColor? = null,
  val bold: Boolean = false,
  val faint: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val strikeout: Boolean = false,
  val inverse: Boolean = false,
) {
  val isDefault: Boolean get() = this == DEFAULT

  companion object {
    val DEFAULT = AnsiStyle()
  }
}

/** A styled span of the parsed text; [start] and [length] are character offsets into [ParsedLine.text]. */
data class StyleRun(val start: Int, val length: Int, val style: AnsiStyle)

/** [runs] only cover spans whose style is not default. [endStyle] is the style in effect at the end of the line. */
data class ParsedLine(val text: String, val runs: List<StyleRun>, val endStyle: AnsiStyle)

private const val ESC = '\u001B'
private const val BEL = '\u0007'

private const val BRIGHT_OFFSET = 8
private const val CUBE_START = 16
private const val GRAY_START = 232
private const val CUBE_BASE = 6
private const val GRAY_BASE = 8
private const val GRAY_STEP = 10
private const val COLOR_MAX = 255
private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

/**
 * Parses one line (no line breaks; CR overwrite and section markers are already handled) into plain text plus
 * style runs. SGR sequences update the style, starting from [initial]; every other escape sequence is dropped.
 * Malformed or truncated sequences never throw and never leave an ESC in the text.
 */
fun parseAnsiLine(line: String, initial: AnsiStyle): ParsedLine = LineParser(line, initial).parse()

private class LineParser(private val line: String, initial: AnsiStyle) {
  private val text = StringBuilder(line.length)
  private val runs = ArrayList<StyleRun>()
  private var style = initial
  private var runStart = 0

  fun parse(): ParsedLine {
    var i = 0
    while (i < line.length) {
      val c = line[i]
      if (c == ESC) {
        i = consumeEscape(i)
      } else {
        text.append(c)
        i++
      }
    }
    closeRun()
    return ParsedLine(text.toString(), runs, style)
  }

  private fun closeRun() {
    if (!style.isDefault && text.length > runStart) runs.add(StyleRun(runStart, text.length - runStart, style))
    runStart = text.length
  }

  private fun changeStyle(next: AnsiStyle) {
    if (next == style) return
    closeRun()
    style = next
  }

  /** Handles the escape sequence starting at [at] and returns the index after it. */
  private fun consumeEscape(at: Int): Int {
    val next = line.getOrNull(at + 1)
    return when {
      next == '[' -> consumeCsi(at + 2)
      next == ']' -> skipOsc(line, at + 2)
      next != null && next in '\u0040'..'\u005F' -> at + 2
      else -> at + 1
    }
  }

  private fun consumeCsi(from: Int): Int {
    var j = from
    while (j < line.length && line[j] in '\u0030'..'\u003F') j++
    val paramsEnd = j
    while (j < line.length && line[j] in '\u0020'..'\u002F') j++
    val final = line.getOrNull(j)
    // A truncated or invalid sequence drops what was read; the offending character stays as text.
    if (final == null || final !in '\u0040'..'\u007E') return j
    if (final == 'm' && paramsEnd == j) changeStyle(applySgr(style, line.substring(from, paramsEnd)))
    return j + 1
  }
}

/** Returns the index after an OSC body starting at [from], ending at BEL or ESC `\`, or the line end. */
private fun skipOsc(line: String, from: Int): Int {
  var j = from
  while (j < line.length) {
    if (line[j] == BEL) return j + 1
    if (line[j] == ESC && line.getOrNull(j + 1) == '\\') return j + 2
    j++
  }
  return line.length
}

private val ATTRIBUTE_CODES: Map<Int, (AnsiStyle) -> AnsiStyle> = mapOf(
  0 to { _ -> AnsiStyle.DEFAULT },
  1 to { s -> s.copy(bold = true) },
  2 to { s -> s.copy(faint = true) },
  3 to { s -> s.copy(italic = true) },
  4 to { s -> s.copy(underline = true) },
  7 to { s -> s.copy(inverse = true) },
  9 to { s -> s.copy(strikeout = true) },
  22 to { s -> s.copy(bold = false, faint = false) },
  23 to { s -> s.copy(italic = false) },
  24 to { s -> s.copy(underline = false) },
  27 to { s -> s.copy(inverse = false) },
  29 to { s -> s.copy(strikeout = false) },
  39 to { s -> s.copy(fg = null) },
  49 to { s -> s.copy(bg = null) },
)

private fun applySgr(current: AnsiStyle, params: String): AnsiStyle {
  val codes = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: -1 }
  var style = current
  var i = 0
  while (i < codes.size) {
    val code = codes[i]
    i++
    if (code == 38 || code == 48) {
      val (color, used) = extendedColor(codes, i)
      i += used
      style = withColor(style, code == 38, color)
    } else {
      style = ATTRIBUTE_CODES[code]?.invoke(style) ?: applyBasicColor(style, code)
    }
  }
  return style
}

private fun withColor(style: AnsiStyle, foreground: Boolean, color: AnsiColor?): AnsiStyle =
  when {
    color == null -> style
    foreground -> style.copy(fg = color)
    else -> style.copy(bg = color)
  }

/** Applies the 30-37/40-47 and bright 90-97/100-107 color codes; any other code leaves [style] unchanged. */
private fun applyBasicColor(style: AnsiStyle, code: Int): AnsiStyle =
  when (code) {
    in 30..37 -> style.copy(fg = AnsiColor.Named(code - 30))
    in 40..47 -> style.copy(bg = AnsiColor.Named(code - 40))
    in 90..97 -> style.copy(fg = AnsiColor.Named(code - 90 + BRIGHT_OFFSET))
    in 100..107 -> style.copy(bg = AnsiColor.Named(code - 100 + BRIGHT_OFFSET))
    else -> style
  }

/** Reads the arguments after a 38/48 at [from]; returns the color (null when invalid) and how many were consumed. */
private fun extendedColor(codes: List<Int>, from: Int): Pair<AnsiColor?, Int> {
  val mode = codes.getOrNull(from) ?: return null to 0
  return when (mode) {
    5 -> {
      val n = codes.getOrNull(from + 1)
      if (n == null) null to 1 else palette256(n) to 2
    }
    2 -> {
      if (from + 3 >= codes.size) return null to (codes.size - from)
      val (r, g, b) = codes.subList(from + 1, from + 4)
      val ok = listOf(r, g, b).all { it in 0..COLOR_MAX }
      (if (ok) AnsiColor.Rgb(r, g, b) else null) to 4
    }
    else -> null to 0
  }
}

private fun palette256(n: Int): AnsiColor? =
  when (n) {
    in 0 until CUBE_START -> AnsiColor.Named(n)
    in CUBE_START until GRAY_START -> {
      val v = n - CUBE_START
      AnsiColor.Rgb(
        CUBE_LEVELS[v / (CUBE_BASE * CUBE_BASE)],
        CUBE_LEVELS[v / CUBE_BASE % CUBE_BASE],
        CUBE_LEVELS[v % CUBE_BASE],
      )
    }
    in GRAY_START..COLOR_MAX -> {
      val g = GRAY_BASE + GRAY_STEP * (n - GRAY_START)
      AnsiColor.Rgb(g, g, g)
    }
    else -> null
  }
