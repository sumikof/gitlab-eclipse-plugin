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
 */
class QuickChatConversation {
  /** One line of the conversation pane. */
  sealed interface Entry {
    data class Question(val text: String) : Entry

    /** The answer being waited for; replaced by the send's result. */
    data object Pending : Entry

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

  /** Replaces the [Entry.Pending] entry with [entry], or appends it when nothing is pending. */
  fun resolvePending(entry: Entry) {
    val index = items.lastIndexOf(Entry.Pending)
    if (index >= 0) items[index] = entry else add(entry)
  }

  /** Stores an answer, truncated to [MAX_ANSWER_BYTES] UTF-8 on a code point boundary (design §9.7). */
  fun storeAnswer(content: String) {
    val kept = Utf8.keepPrefix(content, MAX_ANSWER_BYTES)
    val stored = if (kept.length == content.length) content else "$kept\n\n${QuickChatTexts.ANSWER_TRUNCATED}"
    resolvePending(Entry.Answer(stored))
  }

  /** Puts "New chat" right before the pending send's question (design §9.2.2 step 5, A26). */
  fun insertSeparatorBeforePendingQuestion() {
    val pending = items.lastIndexOf(Entry.Pending)
    if (pending >= 1 && items[pending - 1] is Entry.Question) {
      items.add(pending - 1, Entry.Separator)
      trim()
    }
  }

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
  is Entry.Answer -> InlineThreadEntry(QuickChatTexts.AUTHOR_DUO, "", markdown, codeBlocks = true)
  is Entry.Failure -> InlineThreadEntry(QuickChatTexts.AUTHOR_DUO, "", message)
  Entry.Separator -> InlineThreadEntry("", "", QuickChatTexts.NEW_CHAT)
}
