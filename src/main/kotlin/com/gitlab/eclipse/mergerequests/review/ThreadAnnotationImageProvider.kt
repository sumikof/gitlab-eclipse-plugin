package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.jface.text.source.Annotation
import org.eclipse.swt.graphics.Image
import org.eclipse.ui.texteditor.IAnnotationImageProvider

/** Annotation type of an unresolved MR thread (`plugin.xml`, `org.eclipse.ui.editors.annotationTypes`). */
const val UNRESOLVED_THREAD_ANNOTATION_TYPE = "com.gitlab.eclipse.mrThread.unresolved"

/** Annotation type of a resolved MR thread (FR-3: resolved threads are a separate type). */
const val RESOLVED_THREAD_ANNOTATION_TYPE = "com.gitlab.eclipse.mrThread.resolved"

/**
 * Ruler images of the two MR-thread annotation types, referenced by both
 * `markerAnnotationSpecification`s in `plugin.xml` (same shape as
 * [com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsAnnotationImageProvider]).
 *
 * Reuses the plugin's themed chat icons — filled for an unresolved thread, outlined for a resolved
 * one — so no new image assets are needed. The descriptor ids are namespaced because the editors
 * plugin keys its shared image registry by them. An annotation without an image is not paintable
 * on the vertical ruler (`DefaultMarkerAnnotationAccess.isPaintable`), so both types must resolve.
 */
class ThreadAnnotationImageProvider : IAnnotationImageProvider {
  // Null: the platform builds and caches the image from getImageDescriptorId/getImageDescriptor.
  override fun getManagedImage(annotation: Annotation): Image? = null

  override fun getImageDescriptorId(annotation: Annotation): String? = when (annotation.type) {
    UNRESOLVED_THREAD_ANNOTATION_TYPE -> UNRESOLVED_IMAGE_ID
    RESOLVED_THREAD_ANNOTATION_TYPE -> RESOLVED_IMAGE_ID
    else -> null
  }

  override fun getImageDescriptor(imageDescriptorId: String?): ImageDescriptor? = when (imageDescriptorId) {
    UNRESOLVED_IMAGE_ID -> ThemeUtils.getThemedIcon("chat_on_obj")
    RESOLVED_IMAGE_ID -> ThemeUtils.getThemedIcon("chat_off_obj")
    else -> null
  }

  private companion object {
    const val UNRESOLVED_IMAGE_ID = "com.gitlab.eclipse.mrThread.image.unresolved"
    const val RESOLVED_IMAGE_ID = "com.gitlab.eclipse.mrThread.image.resolved"
  }
}
