package com.gitlab.eclipse.mergerequests.review

/**
 * The review sessions of open documents and the editors connected to each (design §9.1.2, §9.5,
 * §9.6), free of SWT: [D] is the document key (an `IDocument` in production, compared by
 * `equals`) and [E] is an editor.
 *
 * **UI-thread confined.** Nothing here is synchronized: every call, including the background
 * load's [onLoaded] / [onLoadFailed] delivery, must be made on the UI thread (design §17).
 *
 * Each document has at most one entry: an identity, a [Phase], the generation of its latest load,
 * its connected editors, and the last snapshot applied to it. Generations come from one counter
 * that only ever grows — also across [clear] — so a load started for an entry that has since been
 * replaced, refreshed, removed or cleared can never match a later one.
 */
class ReviewSessionState<D : Any, E : Any> {
  enum class Phase { LOADING, READY, FAILED }

  /** What [begin] asks the caller to do. */
  sealed interface BeginResult<D, E> {
    /** The editor joined an existing LOADING or READY session with the same identity; no load is needed. */
    data class Joined<D, E>(val document: D) : BeginResult<D, E>

    /**
     * Run the background load for [generation] and deliver it with [onLoaded] / [onLoadFailed].
     * [replaced] is the snapshot of a session with another identity that this one replaced: the
     * caller removes its annotations and closes its popups (design §9.5 steps 2-3).
     */
    data class StartLoad<D, E>(val document: D, val generation: Long, val replaced: ReviewSessionSnapshot?) :
      BeginResult<D, E>
  }

  private class Entry<E>(
    var identity: SessionIdentity,
    var phase: Phase,
    var generation: Long,
    var snapshot: ReviewSessionSnapshot?,
  ) {
    val editors = LinkedHashSet<E>()
  }

  private val entries = LinkedHashMap<D, Entry<E>>()
  private var lastGeneration = 0L

  /**
   * Connects [editor] to [document]'s session for [identity] (design §9.1.2):
   * - no session: a new LOADING session, and a load starts;
   * - same identity, LOADING or READY: the editor joins ([BeginResult.Joined]);
   * - same identity, FAILED: the editor joins and the session reloads (LOADING, new generation),
   *   keeping every editor already connected (A25);
   * - another identity: the session is replaced (design §9.5). The old generation is invalidated,
   *   its snapshot is returned as [BeginResult.StartLoad.replaced], and the connected editors carry
   *   over to the new LOADING session.
   */
  fun begin(document: D, editor: E, identity: SessionIdentity): BeginResult<D, E> {
    val entry = entries[document]
    return when {
      entry == null -> {
        val created = Entry<E>(identity, Phase.LOADING, nextGeneration(), snapshot = null)
        created.editors.add(editor)
        entries[document] = created
        BeginResult.StartLoad(document, created.generation, replaced = null)
      }
      entry.identity == identity && entry.phase != Phase.FAILED -> {
        entry.editors.add(editor)
        BeginResult.Joined(document)
      }
      else -> {
        val replaced = if (entry.identity == identity) null else entry.snapshot
        entry.editors.add(editor)
        entry.identity = identity
        entry.phase = Phase.LOADING
        entry.generation = nextGeneration()
        if (replaced != null) entry.snapshot = null
        BeginResult.StartLoad(document, entry.generation, replaced)
      }
    }
  }

  /**
   * Applies a successful load. Returns the editors to attach it to — every editor still connected,
   * not only the one that started the load (A21) — or `null` to discard the result: the document
   * has no session any more, or [generation] is not its latest load.
   */
  fun onLoaded(document: D, generation: Long, snapshot: ReviewSessionSnapshot): List<E>? {
    val entry = entries[document]?.takeIf { it.generation == generation && it.editors.isNotEmpty() } ?: return null
    entry.phase = Phase.READY
    entry.snapshot = snapshot
    return entry.editors.toList()
  }

  /**
   * Marks the latest load as FAILED (design §9.1.2). The connected editors are kept so a later
   * [begin] with the same identity reloads for all of them, and the last applied snapshot (if a
   * refresh failed) is kept because its annotations are still attached. A stale [generation] is
   * ignored.
   */
  fun onLoadFailed(document: D, generation: Long) {
    val entry = entries[document]?.takeIf { it.generation == generation } ?: return
    entry.phase = Phase.FAILED
  }

  /**
   * Starts a reload of [document]'s session after a write (FR-10, design §9.4) and returns the
   * generation to load for, or `null` when the document has no session for [identity] (it was
   * replaced or released: the reload is skipped, design §9.5). A READY session stays READY with
   * its annotations until the reload lands; any other session goes to LOADING.
   */
  fun refresh(document: D, identity: SessionIdentity): Long? {
    val entry = entries[document]?.takeIf { it.identity == identity } ?: return null
    if (entry.phase != Phase.READY) entry.phase = Phase.LOADING
    entry.generation = nextGeneration()
    return entry.generation
  }

  /**
   * Disconnects [editor] (design §9.6). When it was the last one, the session is removed whatever
   * its phase and its last applied snapshot is returned so the caller can release the annotations;
   * otherwise (or when nothing was ever applied) returns `null`.
   */
  fun editorClosed(document: D, editor: E): ReviewSessionSnapshot? {
    val entry = entries[document] ?: return null
    if (!entry.editors.remove(editor) || entry.editors.isNotEmpty()) return null
    entries.remove(document)
    return entry.snapshot
  }

  /** The last snapshot applied to [document]'s session, or `null` when none was applied yet. */
  fun snapshot(document: D): ReviewSessionSnapshot? = entries[document]?.snapshot

  /** [document]'s session phase, or `null` when it has no session. */
  fun phase(document: D): Phase? = entries[document]?.phase

  /** Removes every session (bundle stop) and returns their documents so the caller can release them. */
  fun clear(): List<D> {
    val documents = entries.keys.toList()
    entries.clear()
    return documents
  }

  private fun nextGeneration(): Long = ++lastGeneration
}
