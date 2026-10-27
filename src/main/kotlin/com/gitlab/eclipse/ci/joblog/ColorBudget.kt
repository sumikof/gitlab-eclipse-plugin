package com.gitlab.eclipse.ci.joblog

/** Distinct truecolor values one job-log editor creates a `Color` for before it falls back to the 256-color palette. */
const val MAX_EDITOR_COLORS = 1024

private const val XTERM_CHANNEL = 0xFF
private const val XTERM_RED = 16
private const val XTERM_GREEN = 8
private const val XTERM_CUBE_SIDE = 6
private const val XTERM_GRAYS = 24
private const val XTERM_GRAY_FIRST = 8
private const val XTERM_GRAY_STEP = 10

// The six xterm cube levels, as in AnsiSgr (which suppresses MagicNumber file-wide).
@Suppress("MagicNumber")
private val XTERM_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

/**
 * The theme-independent part of the xterm-256 palette (indices 16-255: the 6x6x6 cube and the
 * gray ramp, same values as the SGR parser) as 0xRRGGBB. Indices 0-15 follow the theme, so they
 * are not fallback targets.
 */
private val XTERM_FIXED: IntArray = run {
  val side = XTERM_CUBE_SIDE
  val cube = (0 until side * side * side).map { i ->
    rgbOf(XTERM_CUBE_LEVELS[i / (side * side)], XTERM_CUBE_LEVELS[i / side % side], XTERM_CUBE_LEVELS[i % side])
  }
  val grays = (0 until XTERM_GRAYS).map { i -> (XTERM_GRAY_FIRST + XTERM_GRAY_STEP * i).let { g -> rgbOf(g, g, g) } }
  (cube + grays).toIntArray()
}

private fun rgbOf(r: Int, g: Int, b: Int): Int = (r shl XTERM_RED) or (g shl XTERM_GREEN) or b

private fun channel(rgb: Int, shift: Int): Int = (rgb shr shift) and XTERM_CHANNEL

/** The xterm-256 cube/gray entry closest to [rgb] (squared RGB distance; the first one wins a tie). */
fun nearestXterm256(rgb: Int): Int {
  var best = XTERM_FIXED[0]
  var bestDistance = Int.MAX_VALUE
  for (candidate in XTERM_FIXED) {
    val dr = channel(rgb, XTERM_RED) - channel(candidate, XTERM_RED)
    val dg = channel(rgb, XTERM_GREEN) - channel(candidate, XTERM_GREEN)
    val db = channel(rgb, 0) - channel(candidate, 0)
    val distance = dr * dr + dg * dg + db * db
    if (distance < bestDistance) {
      best = candidate
      bestDistance = distance
    }
  }
  return best
}

/**
 * Caps the distinct colors of one editor (Lead ruling on T5 concern 3). The first [limit] distinct
 * values are used as they are; any later new value is replaced by [nearestXterm256], so an editor
 * holds at most `limit + 240` colors. Admitted values stay admitted, so a color already on screen
 * keeps its `Color` (nothing is disposed while shown). UI thread only.
 */
class ColorBudget(private val limit: Int = MAX_EDITOR_COLORS) {
  private val admitted = HashSet<Int>()

  /** The 0xRRGGBB to create (or reuse) a `Color` for, in place of [rgb]. */
  fun map(rgb: Int): Int {
    if (rgb in admitted) return rgb
    if (admitted.size < limit) {
      admitted.add(rgb)
      return rgb
    }
    return nearestXterm256(rgb)
  }
}
