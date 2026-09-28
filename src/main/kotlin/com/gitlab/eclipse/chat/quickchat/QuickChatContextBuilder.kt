package com.gitlab.eclipse.chat.quickchat

/** Which oversized item [QuickChatContextBuilder.build] refused to send. */
enum class TooLargeItem { QUESTION, SELECTION }

/** The outcome of [QuickChatContextBuilder.build]. */
sealed interface ContextResult {
  /** The fixed context is within every size limit and ready to send. */
  data class Ok(val context: QuickChatContext) : ContextResult

  /** [item] exceeded its size limit (design §9.2.1); the caller must not send this request. */
  data class TooLarge(val item: TooLargeItem) : ContextResult
}

/**
 * Builds the (question, currentFile) pair sent to `aiAction` from a fixed document window and
 * selection, applying the size limits of design §9.2.1 / A21.
 *
 * Reads at most one selection window and up to [ADJACENT_MAX_CHARS] characters on each side of
 * it from [TextWindow] — never the whole document — and never reads the selection at all once its
 * character count alone proves it is too large (design §9.2.1: "選択の文字数が 64 Ki を超えれば、
 * 文字列を取らずに上限超過と判定する").
 */
object QuickChatContextBuilder {
  /** Question size limit in UTF-8 bytes (design §9.2.1). */
  const val MAX_QUESTION_BYTES = 16 * 1024

  /** Selected-text size limit in UTF-8 bytes (design §9.2.1). */
  const val MAX_SELECTION_BYTES = 64 * 1024

  /** Selected-text size limit in characters, checked before reading it (design §9.2.1). */
  const val MAX_SELECTION_CHARS = 64 * 1024

  /** How many characters adjacent to the selection are read from [TextWindow] on each side. */
  const val ADJACENT_MAX_CHARS = 32 * 1024

  /** Above/below context size limit in UTF-8 bytes, after reading, per side (design §9.2.1). */
  const val ADJACENT_MAX_BYTES = 32 * 1024

  /**
   * Builds the context for one question.
   *
   * - [question] over [MAX_QUESTION_BYTES] UTF-8 bytes → [ContextResult.TooLarge] with
   *   [TooLargeItem.QUESTION].
   * - An empty selection ([selectionLength] `== 0`) or a `null` [text] → `currentFile = null`
   *   (design §6.4 R3, A6).
   * - Otherwise the selection, and up to [ADJACENT_MAX_CHARS] characters on each side, are read
   *   from [text] and trimmed to their byte limits, keeping the side nearest the selection and
   *   cutting only on code point boundaries (design §9.2.1, A21).
   */
  fun build(
    question: String,
    fileName: String?,
    text: TextWindow?,
    selectionOffset: Int,
    selectionLength: Int,
  ): ContextResult {
    if (Utf8.byteLength(question) > MAX_QUESTION_BYTES) {
      return ContextResult.TooLarge(TooLargeItem.QUESTION)
    }

    if (selectionLength == 0 || text == null) {
      return ContextResult.Ok(QuickChatContext(question, currentFile = null))
    }

    if (selectionLength > MAX_SELECTION_CHARS) {
      return ContextResult.TooLarge(TooLargeItem.SELECTION)
    }

    val selectedText = text.get(selectionOffset, selectionLength)
    if (Utf8.byteLength(selectedText) > MAX_SELECTION_BYTES) {
      return ContextResult.TooLarge(TooLargeItem.SELECTION)
    }

    val aboveStart = maxOf(0, selectionOffset - ADJACENT_MAX_CHARS)
    val aboveLen = selectionOffset - aboveStart
    val rawAbove = if (aboveLen > 0) text.get(aboveStart, aboveLen) else ""

    val belowStart = selectionOffset + selectionLength
    val belowEnd = minOf(text.length, belowStart + ADJACENT_MAX_CHARS)
    val belowLen = belowEnd - belowStart
    val rawBelow = if (belowLen > 0) text.get(belowStart, belowLen) else ""

    val currentFile = CurrentFile(
      fileName = fileName.orEmpty(),
      selectedText = selectedText,
      // "above" ends right where the selection starts, so the near side is its tail (suffix).
      contentAboveCursor = Utf8.keepSuffix(rawAbove, ADJACENT_MAX_BYTES),
      // "below" starts right where the selection ends, so the near side is its head (prefix).
      contentBelowCursor = Utf8.keepPrefix(rawBelow, ADJACENT_MAX_BYTES),
    )
    return ContextResult.Ok(QuickChatContext(question, currentFile))
  }
}

/**
 * UTF-8 byte accounting that never splits a code point (design §9.2.1: "切り詰めはコードポイント
 * の境界で行う"). Used instead of `String.toByteArray` round-trips for the keep-prefix/keep-suffix
 * cuts, which must stop *before* a code point that would not fit rather than truncate mid-encoding.
 */
internal object Utf8 {
  private const val ONE_BYTE_MAX = 0x7F
  private const val TWO_BYTE_MAX = 0x7FF
  private const val THREE_BYTE_MAX = 0xFFFF
  private const val ONE_BYTE_LEN = 1
  private const val TWO_BYTE_LEN = 2
  private const val THREE_BYTE_LEN = 3
  private const val FOUR_BYTE_LEN = 4

  /** The UTF-8 byte length of [s]. */
  fun byteLength(s: String): Int = s.toByteArray(Charsets.UTF_8).size

  /** Keeps as many complete leading code points of [s] as fit within [maxBytes] UTF-8 bytes. */
  fun keepPrefix(s: String, maxBytes: Int): String {
    if (byteLength(s) <= maxBytes) return s
    var bytes = 0
    var i = 0
    while (i < s.length) {
      val codePoint = s.codePointAt(i)
      val codePointBytes = utf8Length(codePoint)
      if (bytes + codePointBytes > maxBytes) break
      bytes += codePointBytes
      i += Character.charCount(codePoint)
    }
    return s.substring(0, i)
  }

  /** Keeps as many complete trailing code points of [s] as fit within [maxBytes] UTF-8 bytes. */
  fun keepSuffix(s: String, maxBytes: Int): String {
    if (byteLength(s) <= maxBytes) return s
    var bytes = 0
    var i = s.length
    while (i > 0) {
      val codePoint = s.codePointBefore(i)
      val codePointBytes = utf8Length(codePoint)
      if (bytes + codePointBytes > maxBytes) break
      bytes += codePointBytes
      i -= Character.charCount(codePoint)
    }
    return s.substring(i)
  }

  private fun utf8Length(codePoint: Int): Int = when {
    codePoint <= ONE_BYTE_MAX -> ONE_BYTE_LEN
    codePoint <= TWO_BYTE_MAX -> TWO_BYTE_LEN
    codePoint <= THREE_BYTE_MAX -> THREE_BYTE_LEN
    else -> FOUR_BYTE_LEN
  }
}
