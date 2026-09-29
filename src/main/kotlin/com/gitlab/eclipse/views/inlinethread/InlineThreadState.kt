package com.gitlab.eclipse.views.inlinethread

/**
 * The SWT-free state of one inline thread popup: which thread is selected, each thread's draft
 * and edit generation, and whether a submit is in flight (design §9.2, §9.3.1, §29 #20–#22).
 *
 * **UI-thread confined.** Every member must be called on the SWT UI thread; there is no locking.
 * Background work (the launcher's `write`) marshals back to the UI thread before calling
 * [onAttemptFinished] or [onSucceeded].
 *
 * Rules:
 * - Drafts and edit generations are kept per [InlineThreadItem.threadId] (§29 #21). The generation
 *   counts changes of the draft; [onSucceeded] only acts when it is unchanged since [beginSubmit],
 *   so text the user added after pressing send is never wiped.
 * - While [busy], the selection cannot change and nothing new can be submitted. Busy is released
 *   by [onLaunchRejected] or [onAttemptFinished] for the in-flight ticket only (§29 #20).
 * - [unsentDrafts] is what the caller must offer to copy before closing or replacing the popup
 *   (§29 #22), including drafts of threads that vanished in [replaceModel].
 * - A thread whose attempt ended unconfirmed ([onAttemptUnconfirmed], #96) keeps its draft but can
 *   never be submitted again from this popup: the text may already be posted, so re-sending it must
 *   take a deliberate new popup, not one click on the button that just timed out.
 *
 * `@Suppress("TooManyFunctions")`: the twelve members are the fixed API the popup (and later
 * Quick Chat) depends on verbatim; splitting them would scatter one state machine.
 */
@Suppress("TooManyFunctions")
class InlineThreadState(model: InlineThreadModel) {

  var model: InlineThreadModel = requireValid(model)
    private set

  var selectedThreadId: String = model.items.first().threadId
    private set

  val busy: Boolean
    get() = inFlight != null

  /**
   * The ticket of the submit that set [busy]. Compared by identity, never by value: a later
   * submit of the same unedited draft yields an equal ticket, and a late finish of the earlier
   * one (a `[Retry]` of its launch) must not release the newer one.
   */
  private var inFlight: SubmitTicket? = null

  private val drafts = LinkedHashMap<String, String>()

  /** Threads locked by [onAttemptUnconfirmed]; never cleared for the life of this popup. */
  private val unconfirmed = HashSet<String>()
  private val generations = HashMap<String, Long>()

  fun draft(threadId: String): String = drafts[threadId].orEmpty()

  fun editGeneration(threadId: String): Long = generations[threadId] ?: 0L

  /**
   * Records the input field's text for [threadId]. An echo of the current text (for example the
   * widget re-set to the stored draft on a selection change) is not an edit and does not bump the
   * generation; every real change does.
   */
  fun onEdit(threadId: String, text: String) {
    if (draft(threadId) == text) return
    drafts[threadId] = text
    generations[threadId] = editGeneration(threadId) + 1
  }

  /** Selects [threadId]. Returns false while [busy] or when the model has no such thread. */
  fun select(threadId: String): Boolean {
    if (busy || model.items.none { it.threadId == threadId }) return false
    selectedThreadId = threadId
    return true
  }

  fun canSubmit(): Boolean =
    !busy && selectedThreadId !in unconfirmed && isSubmittable(draft(selectedThreadId))

  /**
   * Starts a submit of the selected thread's draft: sets [busy] and freezes the thread id, body
   * and edit generation into the returned ticket. Returns null (and changes nothing) when
   * [canSubmit] is false.
   */
  fun beginSubmit(): SubmitTicket? {
    if (!canSubmit()) return null
    val ticket = SubmitTicket(selectedThreadId, draft(selectedThreadId), editGeneration(selectedThreadId))
    inFlight = ticket
    return ticket
  }

  /** The launcher refused [ticket] (its in-flight guard was taken): release busy right away. */
  fun onLaunchRejected(ticket: SubmitTicket) {
    if (inFlight === ticket) inFlight = null
  }

  /** An attempt for [ticket] returned, whatever its outcome: release busy. */
  fun onAttemptFinished(ticket: SubmitTicket) {
    if (inFlight === ticket) inFlight = null
  }

  /**
   * An attempt for [ticket] ended **unconfirmed** (Ambiguous, #96): it may already be posted.
   * Releases busy exactly like [onAttemptFinished], and in the same call locks [ticket]'s thread
   * against any further submit from this popup, so no UI turn ever renders its Send enabled with
   * the kept draft. The draft itself stays (it is still offered by [unsentDrafts] for copying).
   * A stale ticket still locks its thread but, as in [onAttemptFinished], releases only itself.
   */
  fun onAttemptUnconfirmed(ticket: SubmitTicket) {
    unconfirmed += ticket.threadId
    onAttemptFinished(ticket)
  }

  /**
   * An attempt for [ticket] succeeded. When [ticket]'s thread has not been edited since the
   * ticket was frozen, clears that thread's draft (even if another thread is selected now) and
   * returns [SuccessEffect.CLOSE] for [NEW_THREAD_ID] or [SuccessEffect.CLEAR_DRAFT] for a reply.
   * Otherwise returns [SuccessEffect.NONE] and changes nothing. The ticket's body is not compared:
   * a `[Retry]` may have sent an edited body while the popup itself stayed untouched (§9.3.1).
   */
  fun onSucceeded(ticket: SubmitTicket): SuccessEffect {
    if (editGeneration(ticket.threadId) != ticket.generation) return SuccessEffect.NONE
    // Cleared for CLOSE too, so the close path does not offer to copy the text just sent.
    onEdit(ticket.threadId, "")
    return if (ticket.threadId == NEW_THREAD_ID) SuccessEffect.CLOSE else SuccessEffect.CLEAR_DRAFT
  }

  /** Submittable drafts of every thread seen so far, including threads no longer in [model]. */
  fun unsentDrafts(): List<String> = drafts.values.filter(::isSubmittable)

  /**
   * Swaps the display model. Drafts and generations stay keyed by thread id; if the selected
   * thread is gone, the first item is selected. Busy and the in-flight ticket are not touched.
   */
  fun replaceModel(model: InlineThreadModel) {
    this.model = requireValid(model)
    if (model.items.none { it.threadId == selectedThreadId }) {
      selectedThreadId = model.items.first().threadId
    }
  }

  private companion object {
    fun requireValid(model: InlineThreadModel): InlineThreadModel {
      require(model.items.isNotEmpty()) { "an inline thread model needs at least one item" }
      require(model.items.map { it.threadId }.toSet().size == model.items.size) {
        "inline thread ids must be unique"
      }
      return model
    }
  }
}

/** What one submit froze at [InlineThreadState.beginSubmit]. */
data class SubmitTicket(val threadId: String, val body: String, val generation: Long)

/** What the popup must do after a successful attempt. */
enum class SuccessEffect { NONE, CLEAR_DRAFT, CLOSE }
