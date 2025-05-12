package com.gitlab.eclipse.codesuggestions.annotation

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.jface.text.source.Annotation
import org.eclipse.swt.graphics.Image
import org.eclipse.ui.texteditor.IAnnotationImageProvider

class CodeSuggestionsAnnotationImageProvider : IAnnotationImageProvider {
  private val logger by lazy { logger<CodeSuggestionsAnnotationImageProvider>() }

  // Images created by this method need to be managed by this class.
  // Returning null will result in Eclipse using getImageDescriptorId and getImageDescriptor to create images.
  // The images will be managed by Eclipse image registry.
  override fun getManagedImage(annotation: Annotation): Image? = null

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
