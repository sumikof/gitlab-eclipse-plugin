package com.gitlab.eclipse.chat.quickchat

/** One `aiCompletionResponse` of the stream (design §12). Every field as the server sent it. */
data class StreamFrame(
  val requestId: String?,
  val role: String?,
  val content: String?,
  val errors: List<String>?,
  val chunkId: Int?,
)

/**
 * Builds the text to show while an answer streams (design `quick-chat-streaming` §9.2, §15.3).
 * Display only: it never decides how a send ends (FR-S3). Not thread-safe — the owner locks.
 *
 * Every limit counts UTF-16 chars, not bytes (§15.3). Once [Change.OVERFLOW] has been returned,
 * every later call returns [Change.NONE].
 */
class ChunkAssembler(
  private val maxBufferedChars: Int = QuickChatStreamLimits.MAX_BUFFERED_CHARS,
  private val maxChunks: Int = QuickChatStreamLimits.MAX_CHUNKS,
  private val maxPendingFrames: Int = QuickChatStreamLimits.MAX_PENDING_FRAMES,
) {
  enum class Change { NONE, DISPLAY, OVERFLOW }

  private var requestId: String? = null
  private val pending = ArrayList<StreamFrame>()
  private var pendingChars = 0

  /** Chunks after the contiguous prefix, by chunkId. */
  private val ahead = HashMap<Int, String>()
  private var aheadChars = 0

  /** Every chunkId of the current series (prefix and [ahead]). */
  private val seen = HashSet<Int>()
  private val prefix = StringBuilder()
  private var next = 1
  private var finalText: String? = null
  private var overflowed = false

  var seriesResets = 0
    private set
  var chunksAccepted = 0
    private set
  val finalReceived: Boolean get() = finalText != null

  fun displayText(): String = finalText ?: prefix.toString()

  fun accept(frame: StreamFrame): Change = when {
    overflowed -> Change.NONE
    requestId == null -> hold(frame)
    else -> process(frame)
  }

  /** Fixes the send's requestId and replays the matching held frames in arrival order. Only the first call counts. */
  fun confirmRequestId(requestId: String): Change {
    if (overflowed || this.requestId != null) return Change.NONE
    this.requestId = requestId
    val held = pending.filter { it.requestId == requestId }
    pending.clear()
    pendingChars = 0
    var result = Change.NONE
    for (frame in held) {
      when (process(frame)) {
        Change.OVERFLOW -> return Change.OVERFLOW
        Change.DISPLAY -> result = Change.DISPLAY
        Change.NONE -> Unit
      }
    }
    return result
  }

  private fun hold(frame: StreamFrame): Change {
    val length = frame.content?.length ?: 0
    if (pending.size + 1 > maxPendingFrames || buffered() + length > maxBufferedChars) return overflow()
    pending += frame
    pendingChars += length
    return Change.NONE
  }

  private fun process(frame: StreamFrame): Change {
    if (finalReceived || frame.requestId != requestId || !frame.role.equals(ASSISTANT, ignoreCase = true)) {
      return Change.NONE
    }
    val id = frame.chunkId ?: return acceptFinal(frame)
    return if (id < 1) Change.NONE else addChunk(id, frame.content.orEmpty())
  }

  private fun acceptFinal(frame: StreamFrame): Change {
    if (!frame.errors.isNullOrEmpty()) return Change.NONE
    val text = frame.content.orEmpty()
    if (pendingChars.toLong() + text.length > maxBufferedChars) return overflow()
    clearSeries()
    finalText = text
    return Change.DISPLAY
  }

  private fun addChunk(id: Int, text: String): Change {
    val reset = id in seen
    if (reset) {
      clearSeries()
      seriesResets++
    }
    if (buffered() + text.length > maxBufferedChars || seen.size + 1 > maxChunks) return overflow()
    seen += id
    chunksAccepted++
    val before = prefix.length
    if (id == next) {
      var piece: String? = text
      while (piece != null) {
        prefix.append(piece)
        next++
        piece = ahead.remove(next)?.also { aheadChars -= it.length }
      }
    } else {
      ahead[id] = text
      aheadChars += text.length
    }
    return if (reset || prefix.length > before) Change.DISPLAY else Change.NONE
  }

  private fun clearSeries() {
    ahead.clear()
    aheadChars = 0
    seen.clear()
    prefix.setLength(0)
    next = 1
  }

  private fun overflow(): Change {
    overflowed = true
    return Change.OVERFLOW
  }

  private fun buffered(): Long = pendingChars.toLong() + prefix.length + aheadChars + (finalText?.length ?: 0)

  private companion object {
    const val ASSISTANT = "ASSISTANT"
  }
}
