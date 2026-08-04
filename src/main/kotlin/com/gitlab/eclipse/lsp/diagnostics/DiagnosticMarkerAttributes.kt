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

  fun of(diagnostic: Diagnostic, generation: Long, epoch: Long): Map<String, Any> {
    val attributes = mutableMapOf<String, Any>(
      IMarker.SEVERITY to severityOf(diagnostic.severity),
      IMarker.MESSAGE to messageOf(diagnostic),
      IMarker.LINE_NUMBER to lineOf(diagnostic),
      // source は §17.1 の source 単位の失効に使うため必須。空にできない。
      ATTR_SOURCE to (diagnostic.source?.takeIf { it.isNotBlank() } ?: UNKNOWN_SOURCE),
      ATTR_GENERATION to generation,
      ATTR_EPOCH to epoch,
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
   * (プレーン `String` ではない)。プレーンテキスト(`isLeft`)のみ扱い、`MarkupContent`(`isRight`)
   * は本文を持たない扱いにしてプレースホルダへ落とす([codeOf] の Either 処理と同じパターン)。
   */
  private fun messageOf(diagnostic: Diagnostic): String {
    val text = diagnostic.message?.takeIf { it.isLeft }?.left
    return text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: NO_MESSAGE
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
