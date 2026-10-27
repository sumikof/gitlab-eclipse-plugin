package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.TraceFetch

/** What the last fetched body looked like. All offsets are absolute from the start of the raw log. */
class TraceCursor(
  val totalBytes: Long,
  val displayStartBytes: Long,
  val compareStartBytes: Long,
  val compareBytes: ByteArray,
)

enum class FullReason { INITIAL, SHRUNK, GAP, MISMATCH, HARD_LIMIT }

sealed interface TraceDelta {
  data object Unchanged : TraceDelta

  /** [bytes] start at the previous [TraceCursor.totalBytes]. */
  class Appended(val bytes: ByteArray) : TraceDelta

  data class Full(val reason: FullReason) : TraceDelta
}

/** Decides whether [body] only adds to what [prev] saw, or the document has to be rebuilt. */
fun traceDelta(prev: TraceCursor?, body: TraceFetch.Body, hardLimitBytes: Long = HARD_DISPLAY_RAW_BYTES): TraceDelta {
  if (prev == null) return TraceDelta.Full(FullReason.INITIAL)
  val curStart = body.omittedBytes
  val curEnd = body.totalBytes
  val overlapStart = maxOf(prev.compareStartBytes, curStart)
  return when {
    curEnd < prev.totalBytes -> TraceDelta.Full(FullReason.SHRUNK)
    prev.totalBytes > 0 && overlapStart >= prev.totalBytes -> TraceDelta.Full(FullReason.GAP)
    prev.totalBytes == 0L && curStart > 0 -> TraceDelta.Full(FullReason.GAP)
    overlapDiffers(prev, body, overlapStart) -> TraceDelta.Full(FullReason.MISMATCH)
    curEnd == prev.totalBytes -> TraceDelta.Unchanged
    curEnd - prev.displayStartBytes > hardLimitBytes -> TraceDelta.Full(FullReason.HARD_LIMIT)
    else -> TraceDelta.Appended(body.bytes.copyOfRange((prev.totalBytes - curStart).toInt(), body.bytes.size))
  }
}

private fun overlapDiffers(prev: TraceCursor, body: TraceFetch.Body, overlapStart: Long): Boolean {
  val from = (overlapStart - prev.compareStartBytes).toInt()
  val to = (prev.totalBytes - prev.compareStartBytes).toInt()
  val shift = (prev.compareStartBytes - body.omittedBytes).toInt()
  for (i in from until to) {
    if (prev.compareBytes[i] != body.bytes[i + shift]) return true
  }
  return false
}

/** The cursor to remember after showing [body]; [displayStartBytes] is where the document's raw text starts. */
fun cursorAfter(body: TraceFetch.Body, displayStartBytes: Long, window: Int = COMPARE_WINDOW_BYTES): TraceCursor {
  val size = minOf(window, body.bytes.size)
  return TraceCursor(
    totalBytes = body.totalBytes,
    displayStartBytes = displayStartBytes,
    compareStartBytes = body.totalBytes - size,
    compareBytes = body.bytes.copyOfRange(body.bytes.size - size, body.bytes.size),
  )
}
