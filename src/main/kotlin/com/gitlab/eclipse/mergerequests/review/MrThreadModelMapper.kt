package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadEntry
import com.gitlab.eclipse.views.inlinethread.InlineThreadItem
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.NEW_THREAD_ID
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * SWT-free mapping of a review session's placed threads onto the popup's display model (design
 * §9.2, §11.3, FR-5, FR-6, FR-9). The popup never sees `com.gitlab.eclipse.api`; this is the only
 * place where MR permissions become popup actions.
 */
object MrThreadModelMapper {
  private val CREATED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  /**
   * The threads placed on [oneBasedLine] as one model (in placement order, titled "Thread i of
   * N", keyed by the discussion's reply id), or `null` when the line carries none. A thread
   * without notes (never produced by the loader, which places by the first note) is skipped.
   *
   * Actions follow the same predicates as the sidebar menu: REPLY needs the first note's
   * `createNote` **and** the session's MR-level `createNote` (FR-9); RESOLVE / UNRESOLVE need
   * `resolvable` and the first note's `resolveNote`, picked by the current state. An input field
   * is shown only with REPLY.
   */
  fun threadsAt(
    session: ReviewSessionSnapshot,
    oneBasedLine: Int,
    zone: ZoneId = ZoneId.systemDefault(),
  ): InlineThreadModel? =
    modelOf(session.placements.filter { it.oneBasedLine == oneBasedLine }, session.canCreateNote, zone)

  /**
   * The threads whose discussion reply id is in [threadIds] as one model, like [threadsAt] (placement
   * order, "Thread i of N", same actions), or `null` when none of them is in [session]. This is the
   * lookup for a clicked live line (design §9.2, E4): the ids come from the annotations that sit on
   * that line now, so a thread whose annotation moved with an edit is found by its identity, never
   * confused with another thread that was loaded on the clicked line number.
   */
  fun threadsWithIds(
    session: ReviewSessionSnapshot,
    threadIds: List<String>,
    zone: ZoneId = ZoneId.systemDefault(),
  ): InlineThreadModel? {
    val wanted = threadIds.toSet()
    return modelOf(session.placements.filter { it.discussion.replyId in wanted }, session.canCreateNote, zone)
  }

  private fun modelOf(candidates: List<PlacedThread>, canCreateNote: Boolean, zone: ZoneId): InlineThreadModel? {
    val threads = candidates.filter { it.discussion.notes.isNotEmpty() }
    if (threads.isEmpty()) return null
    val items = threads.mapIndexed { index, placed ->
      val discussion = placed.discussion
      val actions = actionsFor(discussion, canCreateNote)
      InlineThreadItem(
        threadId = discussion.replyId,
        title = "Thread ${index + 1} of ${threads.size}",
        entries = discussion.notes.map { note ->
          InlineThreadEntry(note.authorUsername, formatCreatedAt(note.createdAt, zone), note.body)
        },
        resolved = if (discussion.resolvable) discussion.resolved else null,
        moreEntriesOnServer = discussion.hasMoreNotes,
        actions = actions,
        inputPlaceholder = if (InlineThreadAction.REPLY in actions) REPLY_PLACEHOLDER else null,
      )
    }
    return InlineThreadModel(items)
  }

  /**
   * The single-item model of the "new thread on [oneBasedLine]" popup, or `null` when [session]
   * is loaded and says the account may not comment on the MR (FR-9: refused at execution time;
   * without a session the attempt's G6b decides at send time).
   */
  fun newThread(oneBasedLine: Int, session: ReviewSessionSnapshot?): InlineThreadModel? {
    if (session != null && !session.canCreateNote) return null
    return InlineThreadModel(
      listOf(
        InlineThreadItem(
          threadId = NEW_THREAD_ID,
          title = "New thread on line $oneBasedLine",
          entries = emptyList(),
          resolved = null,
          moreEntriesOnServer = false,
          actions = setOf(InlineThreadAction.CREATE),
          inputPlaceholder = "Comment on line $oneBasedLine…",
        ),
      ),
    )
  }

  /** `yyyy-MM-dd HH:mm` in [zone] for an ISO-8601 instant / offset timestamp; the raw text when it does not parse. */
  fun formatCreatedAt(createdAt: String, zone: ZoneId): String = try {
    OffsetDateTime.parse(createdAt).atZoneSameInstant(zone).format(CREATED_AT)
  } catch (_: DateTimeParseException) {
    createdAt
  }

  private fun actionsFor(discussion: GitLabDiscussion, sessionCanCreateNote: Boolean): Set<InlineThreadAction> {
    val permissions = discussion.notes.first().permissions
    val actions = LinkedHashSet<InlineThreadAction>()
    if (sessionCanCreateNote && permissions.createNote) actions += InlineThreadAction.REPLY
    if (discussion.resolvable && permissions.resolveNote) {
      actions += if (discussion.resolved) InlineThreadAction.UNRESOLVE else InlineThreadAction.RESOLVE
    }
    return actions
  }

  private const val REPLY_PLACEHOLDER = "Reply…"
}
