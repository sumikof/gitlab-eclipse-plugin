package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.chat.quickchat.QuickChatConversation.Entry
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadEntry
import com.gitlab.eclipse.views.inlinethread.InlineThreadItem
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job

/**
 * [InlineThreadItem.threadId] of the single Quick Chat item. Deliberately not `NEW_THREAD_ID`: a
 * successful send must clear the draft, not close the popup (design §9.1 step 5, E6).
 */
const val QUICK_CHAT_ITEM_ID = "quick-chat"

/**
 * One popup's conversation (design §12.1). UI thread only; the background never sees it — it gets an
 * immutable copy of [binding] inside the request, and results come back through `finishOnce`.
 *
 * TooManyFunctions is suppressed: the conversation's small UI-thread operations (in-flight
 * bookkeeping, entry list edits, streamed-partial display) stay together in one class, since they
 * all read and write the same private entry list and in-flight state.
 */
@Suppress("TooManyFunctions")
class QuickChatConversation {
  /** One line of the conversation pane. */
  sealed interface Entry {
    data class Question(val text: String) : Entry

    /** The answer being waited for; replaced by the send's result. */
    data object Pending : Entry

    /**
     * The answer being waited for, with the text streamed so far (streaming design §9.4). Display
     * only: the send's result replaces it exactly like [Pending].
     */
    data class Streaming(val text: String) : Entry

    data class Answer(val markdown: String) : Entry

    data class Failure(val message: String) : Entry

    /** "New chat": later questions no longer continue the earlier ones. */
    data object Separator : Entry
  }

  /**
   * The send in flight (design §12.1): created by [begin], taken off by [clearInFlight]. The fields
   * after [gate] are filled in as `submit` starts the background.
   */
  class InFlight internal constructor(
    val ticket: SubmitTicket,
    val generation: Long,
    val deadlineNanos: Long,
  ) {
    val gate = SendGate()
    var watchdog: Cancellable? = null
    var resultSink: ResultSink? = null
    var job: Job? = null
    var completionHandle: DisposableHandle? = null

    /** The latest streamed text's reader and the scheduled partial render (streaming design §9.4). */
    var progressSource: (() -> String)? = null
    var renderTimer: Cancellable? = null

    /** How long this send's last partial render took, in nanoseconds; widens the next interval (Codex PR #105 P1). */
    var lastRenderNanos: Long = 0
  }

  private val items = ArrayList<Entry>()

  val entries: List<Entry> get() = items.toList()

  /** Older entries were dropped to stay within [MAX_ENTRIES] (design §9.7). */
  var earlierRemoved = false
    private set

  /** Written only by the UI thread in `finishOnce`, `end` and `/clear` `/reset` (design §12.1). */
  var binding: ConversationBinding? = null

  var generation = 0L
    private set

  var inFlight: InFlight? = null
    private set

  fun begin(ticket: SubmitTicket, deadlineNanos: Long): InFlight {
    check(inFlight == null) { "A Quick Chat send is already in flight" }
    return InFlight(ticket, generation, deadlineNanos).also { inFlight = it }
  }

  /**
   * True while [ticket] (this very object: an equal ticket from a later retry is a different send)
   * is the send in flight and no `/clear`, close or reconnect has moved the generation on.
   */
  fun isCurrent(ticket: SubmitTicket, gen: Long): Boolean {
    val send = inFlight ?: return false
    return send.ticket === ticket && send.generation == gen && generation == gen
  }

  fun clearInFlight(): InFlight? = inFlight.also { inFlight = null }

  fun advanceGeneration() {
    generation++
  }

  fun add(entry: Entry) {
    items += entry
    trim()
  }

  /** Replaces the waiting entry ([Entry.Pending] or [Entry.Streaming]) with [entry], or appends it when nothing is waiting. */
  fun resolvePending(entry: Entry) {
    val index = lastWaitingIndex
    if (index >= 0) items[index] = entry else add(entry)
  }

  /** Stores an answer, truncated to [MAX_ANSWER_BYTES] UTF-8 on a code point boundary (design §9.7). */
  fun storeAnswer(content: String) {
    val kept = Utf8.keepPrefix(content, MAX_ANSWER_BYTES)
    val stored = if (kept.length == content.length) content else "$kept\n\n${QuickChatTexts.ANSWER_TRUNCATED}"
    resolvePending(Entry.Answer(stored))
  }

  /**
   * Shows [text] streamed so far in the waiting entry (streaming design §9.4 step 4), cut to
   * [MAX_ANSWER_BYTES]; an empty text shows the plain waiting entry. Does nothing when no entry is
   * waiting: a late partial must never come back after the result.
   */
  fun showPartial(text: String) {
    val index = lastWaitingIndex
    if (index < 0) return
    items[index] = if (text.isEmpty()) Entry.Pending else Entry.Streaming(Utf8.keepPrefix(text, MAX_ANSWER_BYTES))
  }

  /** Puts "New chat" right before the pending send's question (design §9.2.2 step 5, A26). */
  fun insertSeparatorBeforePendingQuestion() {
    val pending = lastWaitingIndex
    if (pending >= 1 && items[pending - 1] is Entry.Question) {
      items.add(pending - 1, Entry.Separator)
      trim()
    }
  }

  /** Index of the entry still waiting for a result ([Entry.Pending] or [Entry.Streaming]), or -1. */
  private val lastWaitingIndex: Int
    get() = items.indexOfLast { it == Entry.Pending || it is Entry.Streaming }

  fun clearEntries() {
    items.clear()
    earlierRemoved = false
  }

  private fun trim() {
    while (items.size > MAX_ENTRIES) {
      items.removeAt(0)
      earlierRemoved = true
    }
  }

  companion object {
    /** Entries kept in the pane (design §9.7). */
    const val MAX_ENTRIES = 40

    /** One answer's stored size in UTF-8 bytes (design §9.7). */
    const val MAX_ANSWER_BYTES = 256 * 1024
  }
}

/** The popup model (design §12.1): one reply-only item holding the whole pane. */
fun QuickChatConversation.toInlineModel(): InlineThreadModel {
  val shown = buildList {
    if (earlierRemoved) add(InlineThreadEntry("", "", QuickChatTexts.EARLIER_REMOVED))
    entries.mapTo(this) { it.toInlineEntry() }
  }
  val item = InlineThreadItem(
    threadId = QUICK_CHAT_ITEM_ID,
    title = QuickChatTexts.ITEM_TITLE,
    entries = shown,
    resolved = null,
    moreEntriesOnServer = false,
    actions = setOf(InlineThreadAction.REPLY),
    inputPlaceholder = QuickChatTexts.INPUT_PLACEHOLDER,
    submitLabel = QuickChatTexts.SEND,
  )
  return InlineThreadModel(listOf(item))
}

private fun Entry.toInlineEntry(): InlineThreadEntry = when (this) {
  is Entry.Question -> InlineThreadEntry(QuickChatTexts.AUTHOR_YOU, "", text)
  Entry.Pending -> InlineThreadEntry(QuickChatTexts.AUTHOR_DUO, "", QuickChatTexts.WAITING)
  is Entry.Streaming ->
    InlineThreadEntry(QuickChatTexts.AUTHOR_DUO, "", "$text\n\n${QuickChatTexts.ANSWER_IN_PROGRESS}")
  is Entry.Answer -> InlineThreadEntry(QuickChatTexts.AUTHOR_DUO, "", markdown, codeBlocks = true)
  is Entry.Failure -> InlineThreadEntry(QuickChatTexts.AUTHOR_DUO, "", message)
  Entry.Separator -> InlineThreadEntry("", "", QuickChatTexts.NEW_CHAT)
}
