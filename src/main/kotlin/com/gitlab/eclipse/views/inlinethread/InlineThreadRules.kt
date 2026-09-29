package com.gitlab.eclipse.views.inlinethread

import org.eclipse.swt.SWT

/**
 * A comment body is submittable when it has non-whitespace content. Whitespace-only input is not
 * a comment: GitLab would reject it and the round trip is wasted. Trimming follows Kotlin's
 * [String.trim] ([Char.isWhitespace]), which is `Character.isWhitespace(c) || Character.isSpaceChar(c)`
 * — so U+00A0 NO-BREAK SPACE is trimmed too, and an NBSP-only body is not submittable. That is the
 * behaviour we want: GitLab renders such a comment as blank.
 */
internal fun isSubmittable(body: String): Boolean = body.trim().isNotEmpty()

/**
 * Whether a key press in the popup's input is the submit chord: `M1+Enter` (Ctrl, or Cmd on macOS),
 * main or keypad Enter, other modifiers ignored. A plain Enter stays a newline.
 */
fun isSubmitChord(stateMask: Int, keyCode: Int): Boolean =
  (stateMask and SWT.MOD1) != 0 && (keyCode == SWT.CR.code || keyCode == SWT.KEYPAD_CR)

/**
 * The header line above an entry's body, omitting empty parts: `"author · createdAt"` (the MR
 * format), the author alone without a time, and no header at all without an author (a Quick Chat
 * marker such as "New chat").
 */
fun entryHeader(entry: InlineThreadEntry): String? = when {
  entry.author.isEmpty() -> null
  entry.createdAt.isEmpty() -> entry.author
  else -> "${entry.author} · ${entry.createdAt}"
}

/**
 * The submit button's text for [item], or `null` for no submit button: only an item with a text
 * action (CREATE or REPLY) gets one, labelled [InlineThreadItem.submitLabel] when set and
 * otherwise "Comment" / "Reply".
 */
fun submitLabelOf(item: InlineThreadItem): String? = when {
  InlineThreadAction.CREATE in item.actions -> item.submitLabel ?: "Comment"
  InlineThreadAction.REPLY in item.actions -> item.submitLabel ?: "Reply"
  else -> null
}
