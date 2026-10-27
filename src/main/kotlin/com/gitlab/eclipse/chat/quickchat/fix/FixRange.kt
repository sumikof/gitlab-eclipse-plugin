// The file pairs the TextSpan value with the fixRange / lineSpanOf functions that use it.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.chat.quickchat.fix

/** A document range: 0-based offset and length (like IDocument / ITextSelection). */
data class TextSpan(val offset: Int, val length: Int) {
  val end: Int get() = offset + length
}

/**
 * VS Code `#getCodeActionRange` (:134-159): a non-empty [selection] wins; a zero-length [target]
 * widens to its line ([lineSpanOf], delimiter excluded); otherwise [target].
 */
fun fixRange(selection: TextSpan?, target: TextSpan, lineSpanOf: (offset: Int) -> TextSpan): TextSpan = when {
  selection != null && selection.length > 0 -> selection
  target.length == 0 -> lineSpanOf(target.offset)
  else -> target
}

/** The delimiter-free span of the line holding an offset, from IDocument-like lookups. */
fun lineSpanOf(
  lineOfOffset: (Int) -> Int,
  lineOffset: (Int) -> Int,
  lineLengthWithoutDelimiter: (Int) -> Int,
): (Int) -> TextSpan = { offset ->
  lineOfOffset(offset).let { line -> TextSpan(lineOffset(line), lineLengthWithoutDelimiter(line)) }
}
