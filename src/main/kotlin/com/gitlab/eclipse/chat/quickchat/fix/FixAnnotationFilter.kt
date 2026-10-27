// The file holds the annotation filter: its fact type, annotation-type constants and selectFixDiagnostics.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.chat.quickchat.fix

data class AnnotationFacts(
  val type: String,
  val text: String?,
  val offset: Int,
  val length: Int,
  val markedDeleted: Boolean,
)

const val ERROR_ANNOTATION_TYPE = "org.eclipse.ui.workbench.texteditor.error"
const val WARNING_ANNOTATION_TYPE = "org.eclipse.ui.workbench.texteditor.warning"

/**
 * Design §5.1: the error / warning annotations that touch [range], as `/fix` diagnostics, in offset
 * order, one per (line, text). A zero-length annotation counts when its line is one of [range]'s
 * lines (genericeditor MarkerResoltionQuickAssistProcessor.java:58-71). [lineOf] is 0-based.
 */
fun selectFixDiagnostics(
  annotations: List<AnnotationFacts>,
  range: TextSpan,
  lineOf: (Int) -> Int,
  isSubtype: (type: String, superType: String) -> Boolean,
): List<FixDiagnostic> {
  val firstLine = lineOf(range.offset)
  val lastLine = lineOf(maxOf(range.offset, range.end - 1))

  fun touches(a: AnnotationFacts): Boolean = when {
    a.length == 0 -> lineOf(a.offset) in firstLine..lastLine
    range.length == 0 -> a.offset <= range.offset && range.offset <= a.offset + a.length
    else -> a.offset < range.end && range.offset < a.offset + a.length
  }

  return annotations
    .filter { !it.markedDeleted && !it.text.isNullOrBlank() }
    .filter { isSubtype(it.type, ERROR_ANNOTATION_TYPE) || isSubtype(it.type, WARNING_ANNOTATION_TYPE) }
    .filter(::touches)
    .sortedBy { it.offset }
    .map { FixDiagnostic(it.text.orEmpty(), lineOf(it.offset) + 1) }
    .distinctBy { it.oneBasedLine to it.message }
}
