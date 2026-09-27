package com.gitlab.eclipse.mergerequests.review

import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.IDocumentListener

/**
 * Whether a new-thread popup's editor document changed at any time after the popup opened (Codex r5).
 * The submit-time check ([newThreadSnapshotStale]) runs only in `onSubmit`; a `[Retry]` re-enters
 * the launcher's write directly, and an edit made while the first attempt was in flight would
 * otherwise go unnoticed (the file on disk still matches the frozen snapshot). Every new-thread
 * attempt therefore reads [edited] first.
 *
 * [install] and [dispose] run on the UI thread (the popup's open and every close path); the
 * listener fires on the thread that changes the document (the UI thread for an editor), and
 * [edited] is read by the background write, hence `@Volatile`. The flag never resets: an edit
 * undone back to the frozen text still refuses the retry (conservative; the user starts the
 * comment again from the line). [dispose] sets it too (fail closed): once the popup is closed its
 * document is no longer followed, so a later `[Retry]` (the popup or editor is gone) is refused.
 * SWT-free.
 */
class NewThreadEditTracker(private val document: IDocument?) {
  @Volatile
  var edited: Boolean = false
    private set

  private var installed = false

  private val listener = object : IDocumentListener {
    override fun documentAboutToBeChanged(event: DocumentEvent) {
      edited = true
    }

    override fun documentChanged(event: DocumentEvent) {
      edited = true
    }
  }

  /** UI thread, when the popup opens. Without a document there is nothing to follow; the submit-time check refuses then. */
  fun install() {
    if (installed || document == null) return
    document.addDocumentListener(listener)
    installed = true
  }

  /** UI thread, on every close path of the popup. Idempotent; from here on [edited] is `true`. */
  fun dispose() {
    edited = true
    if (!installed) return
    installed = false
    document?.removeDocumentListener(listener)
  }
}
