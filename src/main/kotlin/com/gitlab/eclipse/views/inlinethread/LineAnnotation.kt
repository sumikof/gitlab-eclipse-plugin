package com.gitlab.eclipse.views.inlinethread

/**
 * One editor annotation to show on a line (design §11.3): the MR-independent input of
 * [ThreadAnnotationAttacher].
 *
 * @param oneBasedLine the document line, 1-based (converted to the platform's 0-based line only
 *   inside the attacher).
 * @param type the annotation type id declared in `plugin.xml` (`org.eclipse.ui.editors.annotationTypes`).
 * @param hoverText the ruler hover, **already HTML-escaped by the caller**: the platform's
 *   `DefaultAnnotationHover` shows `Annotation.getText()` verbatim and its `HTML2TextReader`
 *   interprets `<` and `&` as markup (design §6.4 E5). A single line is expected: the reader
 *   collapses line breaks into spaces.
 */
data class LineAnnotation(val oneBasedLine: Int, val type: String, val hoverText: String)
