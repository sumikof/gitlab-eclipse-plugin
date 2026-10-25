package com.gitlab.eclipse.ci.joblog

/**
 * Mutable holder for the current rendered job log, shared per [JobLogKey].
 *
 * Writes happen on the UI thread only; `@Volatile` is defensive so any reader
 * observes the latest result. [text] is the document text of [rendered]; setting it
 * replaces the result with an unstyled, fold-free one. With live polling the session empties
 * [rendered] once the shared document holds the text.
 */
class JobLogContent(@Volatile var rendered: RenderResult) {
  constructor(text: String) : this(plain(text))

  /** What the editor shows; the document provider turns it into the editor's status (design B3). */
  @Volatile
  var page: JobLogPage = JobLogPage.Log

  var text: String
    get() = rendered.text
    set(value) {
      rendered = plain(value)
    }

  private companion object {
    fun plain(text: String) = RenderResult(text, StyleRunIndex.EMPTY, emptyList(), omitted = false)
  }
}
