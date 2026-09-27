package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.views.inlinethread.LineAnnotation

/**
 * The ruler-hover text of a placed thread (FR-4): the root note's author, the first line of its
 * body (cut at [MAX_BODY_CHARS]), the reply count and the resolution state, as one line of
 * HTML-escaped text. Free of SWT.
 *
 * Escaped because the platform's `DefaultAnnotationHover` hands `Annotation.getText()` to an HTML
 * presenter without escaping it (design §6.4 E5, verified on `org.eclipse.jface.text` 3.25.200:
 * `formatSingleMessage` only wraps the text in the message format). One line because that reader
 * collapses line breaks into spaces. Only `&`, `<`, `>` and `"` are escaped: they are the entities
 * `HTML2TextReader.entity2Text` knows (`lt`, `gt`, `amp`, `quot`, `nbsp`); `&#39;` would be shown
 * literally, so the apostrophe stays as it is — harmless in text content.
 */
object ThreadHoverText {
  /** Upper bound on the body excerpt, in `String` characters, counted before escaping (FR-4). */
  const val MAX_BODY_CHARS = 200

  private const val ELLIPSIS = "…"
  private const val NO_TEXT = "(no text)"
  private const val UNKNOWN_AUTHOR = "unknown"

  fun of(thread: PlacedThread): String {
    val root = thread.discussion.notes.firstOrNull()
    val author = root?.authorUsername?.ifBlank { null } ?: UNKNOWN_AUTHOR
    val replies = (thread.discussion.notes.size - 1).coerceAtLeast(0)
    val repliesText = when {
      thread.discussion.hasMoreNotes -> "$replies+ replies" // a lower bound: the thread's notes are not paged (§9.1.1)
      replies == 0 -> "no replies"
      replies == 1 -> "1 reply"
      else -> "$replies replies"
    }
    val state = if (thread.resolved) "resolved" else "unresolved"
    return "${escape(author)}: ${escape(excerpt(root?.body.orEmpty()))} — $repliesText, $state"
  }

  /** The first non-blank line of [body], trimmed, cut to [MAX_BODY_CHARS] with a trailing ellipsis. */
  private fun excerpt(body: String): String {
    val line = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return NO_TEXT
    return if (line.length > MAX_BODY_CHARS) line.take(MAX_BODY_CHARS).trimEnd() + ELLIPSIS else line
  }

  private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
}

/**
 * The annotation of one placed thread: its line, the type for its resolution state, its hover, and
 * the discussion's reply id (the thread's identity once the annotation has moved with an edit).
 */
fun PlacedThread.toLineAnnotation(): LineAnnotation = LineAnnotation(
  oneBasedLine = oneBasedLine,
  type = if (resolved) RESOLVED_THREAD_ANNOTATION_TYPE else UNRESOLVED_THREAD_ANNOTATION_TYPE,
  hoverText = ThreadHoverText.of(this),
  threadIds = listOf(discussion.replyId),
)

/** [toLineAnnotation] over a snapshot's placements, in their order. */
fun lineAnnotationsOf(placements: List<PlacedThread>): List<LineAnnotation> = placements.map { it.toLineAnnotation() }
