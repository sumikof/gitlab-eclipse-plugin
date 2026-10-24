package com.gitlab.eclipse.ci.joblog

/** Sections beyond this many that ask to start collapsed are left expanded. */
const val MAX_INITIALLY_COLLAPSED = 500

/** First line of the document (followed by a newline) when the start of the log was cut off. Contains no numbers. */
const val OMITTED_HEADER = "Earlier output was omitted. Use Save Raw Log to save the full log."
