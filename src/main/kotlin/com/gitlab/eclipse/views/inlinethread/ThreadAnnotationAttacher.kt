package com.gitlab.eclipse.views.inlinethread

import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.source.Annotation
import org.eclipse.jface.text.source.AnnotationModel
import org.eclipse.jface.text.source.IAnnotationModel
import org.eclipse.jface.text.source.IAnnotationModelExtension

/**
 * Shows [LineAnnotation]s in the editors of a document by attaching an own sub-model to the
 * document's annotation model (design §8.2, §11.3). MR-independent: it knows lines, type ids and
 * hover strings, nothing about threads.
 *
 * **UI-thread confined**, like every annotation-model change (design §17). One instance keeps one
 * attachment per document, so [detachAll] can release everything at bundle stop.
 *
 * Platform facts relied on (design §6.4, verified on the compile classpath — `org.eclipse.text`
 * `AnnotationModel`):
 * - E3: the editor's model is `documentProvider.getAnnotationModel(input)`; the workspace-file
 *   models (`ResourceMarkerAnnotationModel`, JDT's `CompilationUnitAnnotationModel`) implement
 *   [IAnnotationModelExtension], whose `addAnnotationModel(key, sub)` connects a sub-model. A model
 *   that is `null` or not an extension gets no annotations: [replace] returns `false` and logs once.
 * - E4: the sub-model's positions are registered in the document's default category once the
 *   parent connects it (`addAnnotationModel` calls `connect(document)` for each open connection of
 *   the parent), so they move with edits. The parent forwards the sub-model's change events to its
 *   own listeners, which is what repaints the rulers.
 * - Detach order: `removeAnnotationModel(key)` disconnects the sub-model only while the parent is
 *   still connected (`fOpenConnections` times), and the parent's own `disconnect` cascades to its
 *   attachments first. Detaching therefore works whether `partClosed` arrives before or after the
 *   editor disposed its document provider connection. [detach] removes the annotations first so a
 *   still-connected parent fires one last change and the rulers repaint.
 * - `addAnnotationModel` silently overwrites an entry with the same key without disconnecting it,
 *   so an already attached sub-model under [SUB_MODEL_KEY] (a previous activation that could not
 *   clean up) is reused instead of shadowed.
 */
class ThreadAnnotationAttacher {
  private val logger by lazy { logger<ThreadAnnotationAttacher>() }

  private class Attachment(val parent: IAnnotationModelExtension, val subModel: AnnotationModel) {
    /** The shown annotations, in the order they were given, with their [LineAnnotation.threadIds]. */
    var current: Map<Annotation, List<String>> = emptyMap()
  }

  private val attachments = HashMap<IDocument, Attachment>()

  /**
   * Replaces the annotations shown for [document] with [annotations], attaching the sub-model to
   * [annotationModel] on first use (or moving it when the document's model changed). Returns
   * `false`, after logging one line, when [annotationModel] cannot host a sub-model — the caller
   * keeps its session (design §8.2: commenting still works, only the display is given up).
   * Annotations whose line is outside the document are skipped and counted in the log.
   */
  fun replace(
    document: IDocument,
    annotationModel: IAnnotationModel?,
    annotations: List<LineAnnotation>,
  ): Boolean {
    val parent = annotationModel as? IAnnotationModelExtension
    if (parent == null) {
      logger.warn(
        "Inline thread annotations unavailable: the editor's annotation model does not support sub-models.",
      )
      return false
    }
    val attachment = attachments[document]?.takeIf { it.parent === parent } ?: run {
      detach(document)
      attach(document, parent)
    }
    val added = LinkedHashMap<Annotation, Position>()
    val threadIds = LinkedHashMap<Annotation, List<String>>()
    var skipped = 0
    for (annotation in annotations) {
      val position = linePosition(document, annotation.oneBasedLine)
      if (position == null) {
        skipped += 1
      } else {
        val shown = Annotation(annotation.type, false, annotation.hoverText)
        added[shown] = position
        threadIds[shown] = annotation.threadIds
      }
    }
    attachment.subModel.replaceAnnotations(attachment.current.keys.toTypedArray(), added)
    attachment.current = threadIds
    if (skipped > 0) {
      logger.warn("Inline thread annotations: $skipped of ${annotations.size} lines are outside the document.")
    }
    return true
  }

  /** Removes the sub-model of [document] (and its annotations); a no-op when nothing is attached. */
  fun detach(document: IDocument) {
    val attachment = attachments.remove(document) ?: return
    try {
      attachment.subModel.removeAllAnnotations()
      attachment.parent.removeAnnotationModel(SUB_MODEL_KEY)
    } catch (e: RuntimeException) {
      // A model torn down under us mid-shutdown: nothing is left to show, so nothing is lost.
      logger.warn("Inline thread annotations: detach failed: exceptionType=${e.javaClass.name}")
    }
  }

  /** [detach] for every document (bundle stop). */
  fun detachAll() {
    attachments.keys.toList().forEach(::detach)
  }

  /**
   * True when one of [document]'s shown annotations currently sits on [oneBasedLine]. Uses the
   * live positions (E4), so a line that moved with an edit is found where it is now.
   */
  fun hasAnnotationAt(document: IDocument, oneBasedLine: Int): Boolean =
    annotationsOn(document, oneBasedLine).isNotEmpty()

  /**
   * The [LineAnnotation.threadIds] of the annotations that currently sit on [oneBasedLine] of
   * [document], in the order the annotations were given to [replace], without duplicates. Uses the
   * live positions (E4): after an edit moved an annotation, its ids are found on its new line and
   * no longer on the line it was placed on. Empty when nothing is shown there.
   */
  fun threadIdsAt(document: IDocument, oneBasedLine: Int): List<String> =
    annotationsOn(document, oneBasedLine).flatMap { it.value }.distinct()

  private fun annotationsOn(document: IDocument, oneBasedLine: Int): List<Map.Entry<Annotation, List<String>>> {
    val attachment = attachments[document] ?: return emptyList()
    return attachment.current.entries.filter { (annotation, _) ->
      val position = attachment.subModel.getPosition(annotation)
      position != null && !position.isDeleted && lineOf(document, position.offset) == oneBasedLine - 1
    }
  }

  private fun attach(document: IDocument, parent: IAnnotationModelExtension): Attachment {
    val existing = parent.getAnnotationModel(SUB_MODEL_KEY)
    val subModel = if (existing is AnnotationModel) {
      existing.removeAllAnnotations()
      existing
    } else {
      if (existing != null) parent.removeAnnotationModel(SUB_MODEL_KEY)
      AnnotationModel().also { parent.addAnnotationModel(SUB_MODEL_KEY, it) }
    }
    return Attachment(parent, subModel).also { attachments[document] = it }
  }

  private fun linePosition(document: IDocument, oneBasedLine: Int): Position? {
    if (oneBasedLine < 1) return null
    return try {
      val line = document.getLineInformation(oneBasedLine - 1)
      Position(line.offset, line.length)
    } catch (_: BadLocationException) {
      null
    }
  }

  private fun lineOf(document: IDocument, offset: Int): Int = try {
    document.getLineOfOffset(offset)
  } catch (_: BadLocationException) {
    -1
  }

  companion object {
    /** Key of the sub-model in the editor's annotation model (design §8.2). */
    const val SUB_MODEL_KEY = "com.gitlab.eclipse.mrReviewThreads"
  }
}
