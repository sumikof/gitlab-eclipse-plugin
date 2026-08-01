package com.gitlab.eclipse.ci.joblog

/**
 * Mutable holder for the current job-log text, shared per [JobLogKey].
 *
 * Writes happen on the UI thread only; `@Volatile` is defensive so any reader
 * observes the latest text.
 */
class JobLogContent(@Volatile var text: String)
