package com.gitlab.eclipse.ci.joblog

/** Sections beyond this many that ask to start collapsed are left expanded. */
const val MAX_INITIALLY_COLLAPSED = 500

/** First line of the document (followed by a newline) when the start of the log was cut off. Contains no numbers. */
const val OMITTED_HEADER = "Earlier output was omitted. Use Save Raw Log to save the full log."

/**
 * Most raw trace bytes kept for display (the tail). Provisional value: adjust after trying real logs on a machine
 * (R4). Twice this is the hard limit for live following.
 */
const val MAX_DISPLAY_RAW_BYTES: Int = 16 * 1024 * 1024

/** Hard limit of raw bytes shown while following a live log; beyond it the document is rebuilt from the tail. */
const val HARD_DISPLAY_RAW_BYTES: Long = 2L * MAX_DISPLAY_RAW_BYTES

/** How much of the previous body's end is kept to check that a new body still starts with the old one. Provisional. */
const val COMPARE_WINDOW_BYTES: Int = 64 * 1024
