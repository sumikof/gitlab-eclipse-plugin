package com.gitlab.eclipse.utils.ruler

import org.eclipse.jface.text.Position
import org.eclipse.jface.text.source.*
import org.eclipse.jface.text.source.Annotation
import org.eclipse.swt.graphics.Font
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess
import org.eclipse.ui.texteditor.rulers.AbstractContributedRulerColumn

@Suppress("MagicNumber")
class DuoAnnotationsRulerColumn : AbstractContributedRulerColumn() {
  private val column: AnnotationRulerColumn = AnnotationRulerColumn(16, DefaultMarkerAnnotationAccess())
  private val columnModel = AnnotationModel()

  init {
    column.model = columnModel
    column.addAnnotationType("codeSuggestionsMarker")
  }

  fun add(annotation: Annotation, position: Position) {
    columnModel.addAnnotation(annotation, position)
  }

  fun replace(oldAnnotation: Annotation, newAnnotation: Annotation, position: Position) {
    columnModel.replaceAnnotations(
      arrayOf(oldAnnotation),
      mapOf(newAnnotation to position),
    )
  }

  fun remove(annotation: Annotation) {
    columnModel.removeAnnotation(annotation)
  }

  override fun redraw() {
    column.redraw()
  }

  override fun createControl(
    parentRuler: CompositeRuler,
    parentControl: Composite
  ): Control? {
    return column.createControl(parentRuler, parentControl)
  }

  override fun getControl(): Control? = column.control

  override fun getWidth() = column.width

  override fun setFont(font: Font) {
    column.setFont(font)
  }

  override fun setModel(model: IAnnotationModel) {
    // do nothing, we use a different annotation model from the main one
  }
}
