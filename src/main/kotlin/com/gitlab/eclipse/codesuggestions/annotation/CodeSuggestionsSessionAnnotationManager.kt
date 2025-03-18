package com.gitlab.eclipse.codesuggestions.annotation

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.ruler.DuoAnnotationsRulerColumn
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.source.CompositeRuler
import org.eclipse.jface.text.source.IVerticalRulerInfo
import org.eclipse.ui.texteditor.ITextEditor

class CodeSuggestionsSessionAnnotationManager(private val textEditor: ITextEditor) {
  private val logger by lazy { logger<CodeSuggestionsSessionAnnotationManager>() }

  private val duoAnnotationsRulerColumn by lazy {
    val compositeRuler = textEditor.getAdapter(IVerticalRulerInfo::class.java) as? CompositeRuler

    compositeRuler?.decoratorIterator?.forEach { column ->
      if (column is DuoAnnotationsRulerColumn) {
        return@lazy column
      }
    }

    logger.warn("Could not find DuoAnnotationsRulerColumn in CompositeRuler.")
    null
  }

  private var currentAnnotation: CodeSuggestionAnnotation? = null

  fun display(annotationType: CodeSuggestionAnnotationType, offset: Int) {
    val newAnnotation = CodeSuggestionAnnotation(annotationType)
    val position = Position(offset)

    val annotationToReplace = currentAnnotation
    when {
      annotationToReplace != null -> duoAnnotationsRulerColumn?.replace(annotationToReplace, newAnnotation, position)
      else -> duoAnnotationsRulerColumn?.add(newAnnotation, position)
    }

    currentAnnotation = newAnnotation
  }

  fun hide() {
    try {
      currentAnnotation?.let { duoAnnotationsRulerColumn?.remove(it) }
      currentAnnotation = null
    } catch (e: Exception) {
      logger.error("Error hiding code suggestion annotation.", e)
    }
  }
}
