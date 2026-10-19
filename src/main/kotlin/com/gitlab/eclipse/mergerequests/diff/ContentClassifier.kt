package com.gitlab.eclipse.mergerequests.diff

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * One diff side ready for Compare: the untouched [bytes], the viewer-selecting [type] and the
 * [charset] to decode text with (`null` = Compare's platform default).
 */
class ClassifiedContent(val bytes: ByteArray, val type: String, val charset: String?)

/**
 * Picks Compare's viewer type and text charset for a diff side (REVIEW-01 design §5.1, §8).
 *
 * The type is the basename's lowercase extension (`txt` when there is none), which is how Compare
 * chooses a content viewer; content that looks binary is forced to `binary` so it opens in the
 * binary viewer, except for image extensions, which keep the image viewer. The charset is UTF-8
 * only when the content is provably UTF-8 (a BOM, or a strict decode); anything else is left to the
 * platform default rather than guessed. Bytes are never altered, so line endings survive.
 */
object ContentClassifier {
  private const val BINARY_PROBE_LENGTH = 8000
  private const val UTF_8 = "UTF-8"
  private const val TEXT_TYPE = "txt"
  private const val BINARY_TYPE = "binary"

  /** Extensions Compare's ImageMergeViewer handles. */
  private val IMAGE_EXTENSIONS = setOf("gif", "jpg", "jpeg", "png", "bmp", "ico", "tif", "tiff")
  private val UTF_8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

  /** Classifies [bytes] of a file named [basename]; [forceBinary] is shared by both sides of a diff. */
  fun classify(basename: String, bytes: ByteArray, forceBinary: Boolean): ClassifiedContent {
    val extension = basename.substringAfterLast('.', "").lowercase().ifEmpty { TEXT_TYPE }
    val type = if (forceBinary && extension !in IMAGE_EXTENSIONS) BINARY_TYPE else extension
    return ClassifiedContent(bytes, type, charsetOf(bytes))
  }

  /** Whether [bytes] hold a NUL within the first [BINARY_PROBE_LENGTH] bytes. */
  fun looksBinary(bytes: ByteArray): Boolean =
    (0 until minOf(bytes.size, BINARY_PROBE_LENGTH)).any { bytes[it] == 0.toByte() }

  private fun charsetOf(bytes: ByteArray): String? {
    if (bytes.size >= UTF_8_BOM.size && bytes.copyOfRange(0, UTF_8_BOM.size).contentEquals(UTF_8_BOM)) return UTF_8
    return try {
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
      UTF_8
    } catch (_: CharacterCodingException) {
      null
    }
  }
}
