package com.gitlab.eclipse.codesuggestions.annotation

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.jface.text.source.Annotation
import org.eclipse.swt.graphics.Image
import org.eclipse.ui.texteditor.IAnnotationImageProvider

class CodeSuggestionsAnnotationImageProvider : IAnnotationImageProvider {
  private val logger by lazy { logger<CodeSuggestionsAnnotationImageProvider>() }

  override fun getManagedImage(annotation: Annotation): Image? {
    return try {
      val codeSuggestionsAnnotation = annotation as CodeSuggestionAnnotation

      when (codeSuggestionsAnnotation.type) {
        CodeSuggestionAnnotationType.LOADING -> ThemeUtils.getThemedIcon("duo_load_edit")?.createImage()
        CodeSuggestionAnnotationType.READY -> ThemeUtils.getThemedIcon("duo_on_edit")?.createImage()
      }
    } catch (e: ClassCastException) {
      logger.error("Annotation ${annotation.type} is not a CodeSuggestionAnnotation.", e)
      null
    } catch (e: Exception) {
      logger.warn("Unable to load image for annotation ${annotation.type}", e)
      null
    }
  }

  override fun getImageDescriptorId(annotation: Annotation): String? {
    return try {
      val codeSuggestionsAnnotation = annotation as CodeSuggestionAnnotation

      when (codeSuggestionsAnnotation.type) {
        CodeSuggestionAnnotationType.LOADING -> "duo_load_edit"
        CodeSuggestionAnnotationType.READY -> "duo_on_edit"
      }
    } catch (e: ClassCastException) {
      logger.error("Annotation ${annotation.type} is not a CodeSuggestionAnnotation.", e)
      null
    }
  }

  override fun getImageDescriptor(imageDescriptorId: String?): ImageDescriptor? {
    return when (imageDescriptorId) {
      "duo_load_edit" -> ThemeUtils.getThemedIcon("duo_load_edit")
      "duo_on_edit" -> ThemeUtils.getThemedIcon("duo_on_edit")
      else -> null
    }
  }
}
