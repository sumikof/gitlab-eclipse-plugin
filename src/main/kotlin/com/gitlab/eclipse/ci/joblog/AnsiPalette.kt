@file:Suppress("MagicNumber")

package com.gitlab.eclipse.ci.joblog

/** A style reduced to concrete 0xRRGGBB colors; null colors mean "use the editor default". */
data class ResolvedStyle(
  val fgRgb: Int?,
  val bgRgb: Int?,
  val bold: Boolean,
  val italic: Boolean,
  val underline: Boolean,
  val strikeout: Boolean,
)

/** The 16 named terminal colors as 0xRRGGBB for a light or dark editor background. */
object AnsiPalette {
  private val dark = intArrayOf(
    0x000000, 0xC91B00, 0x00A600, 0xC7C400, 0x0225C7, 0xCA30C7, 0x00C5C7, 0xC7C7C7,
    0x686868, 0xFF6E67, 0x5FFA68, 0xFFFC67, 0x6871FF, 0xFF77FF, 0x60FDFF, 0xFFFFFF,
  )

  // Same as dark, except the entries that are hard to read on a white background.
  private val light = dark.copyOf().also {
    it[3] = 0x9A7B00
    it[7] = 0x6E6E6E
    it[11] = 0xB58900
    it[14] = 0x0098A6
    it[15] = 0x3A3A3A
  }

  fun rgb(index: Int, isDark: Boolean): Int = (if (isDark) dark else light)[index.coerceIn(0, dark.size - 1)]
}

private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

private fun toRgb(color: AnsiColor, dark: Boolean): Int =
  when (color) {
    is AnsiColor.Named -> AnsiPalette.rgb(color.index, dark)
    is AnsiColor.Rgb -> (color.r shl RED_SHIFT) or (color.g shl GREEN_SHIFT) or color.b
  }

private fun average(a: Int, b: Int): Int {
  fun channel(shift: Int) = (((a shr shift) and CHANNEL_MASK) + ((b shr shift) and CHANNEL_MASK)) / 2
  return (channel(RED_SHIFT) shl RED_SHIFT) or (channel(GREEN_SHIFT) shl GREEN_SHIFT) or channel(0)
}

/**
 * Resolves [style] to RGB values using the light or dark palette. Inverse swaps fg and bg (a missing side falls
 * back to the editor default); faint blends the fg halfway into the bg.
 */
fun resolveStyle(style: AnsiStyle, dark: Boolean, defaultFgRgb: Int, defaultBgRgb: Int): ResolvedStyle {
  var fg = style.fg?.let { toRgb(it, dark) }
  var bg = style.bg?.let { toRgb(it, dark) }
  if (style.inverse) {
    val swappedFg = bg ?: defaultBgRgb
    bg = fg ?: defaultFgRgb
    fg = swappedFg
  }
  if (style.faint) fg = average(fg ?: defaultFgRgb, bg ?: defaultBgRgb)
  return ResolvedStyle(fg, bg, style.bold, style.italic, style.underline, style.strikeout)
}
