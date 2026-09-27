package com.gitlab.eclipse.views.inlinethread

/**
 * Display model of the non-modal inline thread popup (design §11.3). It is deliberately free of
 * merge-request types so a later Quick Chat cycle can reuse the popup: the MR layer maps its
 * discussions onto these classes, and the popup never sees `com.gitlab.eclipse.api`.
 */
data class InlineThreadModel(val items: List<InlineThreadItem>)

/**
 * One selectable thread in the popup ("Thread 1 of N", design §9.2).
 *
 * @param threadId stable key for drafts and edit generations: the discussion's reply id for an
 *   existing MR thread, [NEW_THREAD_ID] for the "new thread" mode.
 * @param resolved `null` when the thread has no notion of resolution.
 * @param moreEntriesOnServer the server holds entries that [entries] does not (partial fetch,
 *   design §9.1.1), so the popup can say the list is incomplete.
 * @param actions the buttons to render; actions the user may not perform are simply absent.
 * @param inputPlaceholder `null` means the thread has no input field.
 */
data class InlineThreadItem(
  val threadId: String,
  val title: String,
  val entries: List<InlineThreadEntry>,
  val resolved: Boolean?,
  val moreEntriesOnServer: Boolean,
  val actions: Set<InlineThreadAction>,
  val inputPlaceholder: String?,
)

/** One rendered message of a thread. [createdAt] is display text, already formatted. */
data class InlineThreadEntry(val author: String, val createdAt: String, val body: String)

enum class InlineThreadAction { REPLY, RESOLVE, UNRESOLVE, CREATE }

/** [InlineThreadItem.threadId] of the "create a new thread" item. */
const val NEW_THREAD_ID = "new"
