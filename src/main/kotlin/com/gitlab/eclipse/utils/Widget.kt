package com.gitlab.eclipse.utils

import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.Font
import org.eclipse.swt.widgets.Widget

fun Widget.makeBoldFont(font: Font): Font {
  val fontData = font.fontData

  for (fd in fontData) {
    fd.setStyle(SWT.BOLD)
  }

  val boldFont = Font(currentDisplay, fontData)
  addDisposeListener { boldFont.dispose() }

  return boldFont
}

@Suppress("MagicNumber")
fun Widget.makeHintFont(font: Font): Font {
  val fontData = font.fontData

  for (fd in fontData) {
    fd.setStyle(SWT.ITALIC)
    fd.setHeight(10)
  }

  val hintFont = Font(currentDisplay, fontData)
  addDisposeListener { hintFont.dispose() }

  return hintFont
}
