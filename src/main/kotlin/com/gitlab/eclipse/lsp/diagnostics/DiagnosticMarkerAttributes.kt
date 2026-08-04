package com.gitlab.eclipse.lsp.diagnostics

import org.eclipse.core.resources.IMarker
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * `Diagnostic` → marker 属性の純写像(§11.2)。
 *
 * SWT にも IResource にも依存しないので headless で完全にテストできる。
 * CHAR_START / CHAR_END は**設定しない**(行・桁からは求まらず、編集中バッファとディスク内容も
 * 一致しないため。§11.2)。
 */
object DiagnosticMarkerAttributes {
  const val TYPE = "com.gitlab.eclipse.gitlab-eclipse-plugin.gitlabDiagnostic"
  const val ATTR_SOURCE = "com.gitlab.eclipse.diagnosticSource"
  const val ATTR_CODE = "com.gitlab.eclipse.diagnosticCode"
  const val ATTR_GENERATION = "com.gitlab.eclipse.diagnosticGeneration"
  const val ATTR_EPOCH = "com.gitlab.eclipse.diagnosticEpoch"

  const val UNKNOWN_SOURCE = "(unknown)"
  private const val NO_MESSAGE = "(no message)"
  private val WHITESPACE = Regex("\\s+")

  /**
   * Upper bound on the stored message, ellipsis included.
   *
   * The Problems view renders a single line, so nothing near this length is ever readable anyway,
   * and staying far below the limit keeps the workspace's own size handling out of play: it
   * fast-returns for string attributes shorter than 21000 characters and otherwise measures their
   * UTF-8 length, reporting an oversized value by asserting with a large slice of that value in the
   * failure message. That would copy the diagnostic body into the error log, so the cap is an order
   * of magnitude below the threshold and the check can never be reached.
   */
  private const val MESSAGE_MAX_LENGTH = 2000
  private const val ELLIPSIS = "…"

  /**
   * `Eclipse Core Resources` の `MarkerInfo.checkValidAttribute` は `null` / `String` /
   * `Boolean` / `Integer` **以外**を渡すと `IllegalArgumentException`(`CoreException` ではない)
   * で落とす(javap で確認済み)。`generation` / `epoch` は API 全体で `Long` だが、marker 属性へは
   * **`String` として渡す**(`Int` へ縮小すると `Long` を静かに切り捨てるため。`String` は
   * `Long` を可逆に往復できる)。
   */
  fun of(diagnostic: Diagnostic, generation: Long, epoch: Long): Map<String, Any> {
    val attributes = mutableMapOf<String, Any>(
      IMarker.SEVERITY to severityOf(diagnostic.severity),
      IMarker.MESSAGE to messageOf(diagnostic),
      IMarker.LINE_NUMBER to lineOf(diagnostic),
      // source は §17.1 の source 単位の失効に使うため必須。空にできない。
      ATTR_SOURCE to (diagnostic.source?.takeIf { it.isNotBlank() } ?: UNKNOWN_SOURCE),
      ATTR_GENERATION to generation.toString(),
      ATTR_EPOCH to epoch.toString(),
    )
    codeOf(diagnostic)?.let { attributes[ATTR_CODE] = it }
    return attributes
  }

  private fun severityOf(severity: DiagnosticSeverity?): Int = when (severity) {
    DiagnosticSeverity.Error -> IMarker.SEVERITY_ERROR
    DiagnosticSeverity.Warning -> IMarker.SEVERITY_WARNING
    else -> IMarker.SEVERITY_INFO
  }

  /**
   * Problems ビューは 1 行表示。LS は "<name>\n\n<description>" を送る(§6.1 P3)。
   *
   * この repo の lsp4j バージョンでは `Diagnostic.message` は `Either<String, MarkupContent>`
   * (プレーン `String` ではない)。プレーンテキスト(`isLeft`)ならその文字列を、
   * `MarkupContent`(`isRight`)なら `getValue()` の本文テキストを使う(javap で確認済み:
   * `MarkupContent.getValue(): String`)。どちらの経路でも同じ空白圧縮・trim・空白プレースホルダ
   * 処理を適用する(右側を無条件にプレースホルダへ落とすと本文を丸ごと失うため)。
   *
   * 長さの上限([MESSAGE_MAX_LENGTH])は**空白圧縮のあと**に掛ける。表示される形に対する上限であり、
   * 圧縮で十分短くなるメッセージを切り詰めないため。
   */
  private fun messageOf(diagnostic: Diagnostic): String {
    val message = diagnostic.message
    val text = when {
      message == null -> null
      message.isLeft -> message.left
      message.isRight -> message.right?.value
      else -> null
    }
    val collapsed = text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: NO_MESSAGE
    if (collapsed.length <= MESSAGE_MAX_LENGTH) return collapsed
    return collapsed.take(MESSAGE_MAX_LENGTH - ELLIPSIS.length) + ELLIPSIS
  }

  /** LSP は 0 始まり、IMarker は 1 始まり。LS は負の値も出しうる(§6.1 P3)。 */
  private fun lineOf(diagnostic: Diagnostic): Int {
    val line = diagnostic.range?.start?.line ?: 0
    return maxOf(0, line) + 1
  }

  private fun codeOf(diagnostic: Diagnostic): String? {
    val code = diagnostic.code ?: return null
    return when {
      code.isLeft -> code.left?.takeIf { it.isNotBlank() }
      code.isRight -> code.right?.toString()
      else -> null
    }
  }
}
