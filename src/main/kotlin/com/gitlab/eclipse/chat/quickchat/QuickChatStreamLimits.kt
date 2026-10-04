package com.gitlab.eclipse.chat.quickchat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Limits of the Quick Chat answer stream (design `quick-chat-streaming` §15). The stream is only a
 * display aid, so every limit stops the stream — never the send.
 */
object QuickChatStreamLimits {
  /** How long a send waits for `confirm_subscription` before sending without a stream (§15.1). */
  val SUBSCRIBE_WAIT: Duration = 3.seconds

  /** Everything the assembler holds, counted in UTF-16 chars (≈ 2 bytes each, so ~1 MiB) (§15.3). */
  const val MAX_BUFFERED_CHARS: Int = 512 * 1024

  /** Chunks held for one series, including those after a gap (§15.3). */
  const val MAX_CHUNKS: Int = 4096

  /** Frames held before the send's `requestId` is known (§15.3). */
  const val MAX_PENDING_FRAMES: Int = 256

  /** Everything one connection may receive, dropped frames included (§15.3, Codex round 2 #1). */
  const val MAX_RECEIVED_CHARS: Long = 4L * 1024 * 1024

  /** Frames one connection may receive, pings and dropped frames included (§15.3). */
  const val MAX_RECEIVED_FRAMES: Int = 20_000
}
