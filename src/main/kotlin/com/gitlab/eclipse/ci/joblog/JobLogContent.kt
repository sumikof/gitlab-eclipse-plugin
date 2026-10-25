package com.gitlab.eclipse.ci.joblog

/**
 * Mutable holder for the current rendered job log, shared per [JobLogKey].
 *
 * Writes happen on the UI thread only; `@Volatile` is defensive so any reader
 * observes the latest result. [text] is the document text of [rendered]; setting it
 * replaces the result with an unstyled, fold-free one.
 */
class JobLogContent(@Volatile var rendered: RenderResult) {
  constructor(text: String) : this(plain(text))

  var text: String
    get() = rendered.text
    set(value) {
      rendered = plain(value)
    }

  private companion object {
    fun plain(text: String) = RenderResult(text, StyleRunIndex.EMPTY, emptyList(), omitted = false)
  }
}
