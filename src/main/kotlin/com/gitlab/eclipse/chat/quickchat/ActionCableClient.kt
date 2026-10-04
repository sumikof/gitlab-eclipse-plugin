package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.utils.logger
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred

/** Why an [ActionCableClient] stopped (design `quick-chat-streaming` §11.1, §15.3, §17). */
enum class CableStop { REJECTED, DISCONNECTED, CLOSED_BY_SERVER, ERROR, OVERFLOW, LISTENER_FAILED, CLOSED_BY_CLIENT }

/**
 * One ActionCable subscription to `GraphqlChannel` over one [CableSocket] (§11.1).
 *
 * Every callback runs on a transport thread and never throws (§17): state is guarded by a single
 * lock, [stop] may be called from any thread at any time (first reason wins), and [onMessage] is
 * always invoked outside the lock because its consumer takes its own lock. Received chars and
 * frames are counted, dropped frames included, and any limit stops the stream (§15.3). Only stop
 * reasons and exception class names are logged — never identifiers, tokens, URLs or frame text
 * (§19).
 */
class ActionCableClient(
  private val identifier: String,
  private val onMessage: (JsonObject) -> Unit,
  private val maxFrameChars: Int = QuickChatStreamLimits.MAX_BUFFERED_CHARS,
  maxReceivedChars: Long = QuickChatStreamLimits.MAX_RECEIVED_CHARS,
  maxReceivedFrames: Int = QuickChatStreamLimits.MAX_RECEIVED_FRAMES,
) : CableListener {
  /** `true` once `confirm_subscription` arrives; `false` if the client stopped first. */
  val confirmation: CompletableDeferred<Boolean> = CompletableDeferred()

  private val lock = Any()

  // All guarded by [lock].
  private var socket: CableSocket? = null
  private var stopped: CableStop? = null
  private var subscribeSent = false
  private val frame = StringBuilder()
  private val received = ReceiveCounter(maxReceivedChars, maxReceivedFrames)

  val stopReason: CableStop?
    get() = synchronized(lock) { stopped }

  /** Rule 1: keep the socket and ask for the first frame, or abort it if already stopped. */
  override fun onOpen(socket: CableSocket) = guarded {
    val alreadyStopped = synchronized(lock) {
      if (stopped == null) this.socket = socket
      stopped != null
    }
    if (alreadyStopped) socket.abort() else socket.request(1)
  }

  /** Rule 2: count, join and bound frames; handle a complete frame; then ask for the next one. */
  override fun onText(data: CharSequence, last: Boolean) = guarded {
    var overflow = false
    val complete: String? = synchronized(lock) {
      if (stopped != null) return@guarded
      overflow = received.add(data.length, last) || frame.length.toLong() + data.length > maxFrameChars
      when {
        overflow -> null
        !last -> {
          frame.append(data)
          null
        }
        else -> frame.append(data).toString().also { frame.setLength(0) }
      }
    }
    if (overflow) {
      stop(CableStop.OVERFLOW)
      return@guarded
    }
    if (complete != null) parse(complete)?.let(::handle)
    synchronized(lock) { socket.takeIf { stopped == null } }?.request(1)
  }

  /**
   * Binary parts are never parsed but count toward the receive limits (§15.3): their bytes are added
   * as-is to the received chars, and a frame is counted on `last`.
   */
  override fun onBinary(size: Int, last: Boolean) = guarded {
    val overflow = synchronized(lock) {
      if (stopped != null) return@guarded
      received.add(size, last)
    }
    if (overflow) {
      stop(CableStop.OVERFLOW)
    } else {
      synchronized(lock) { socket.takeIf { stopped == null } }?.request(1)
    }
  }

  /** Rule 5. */
  override fun onClosed() = guarded { stop(CableStop.CLOSED_BY_SERVER) }

  /** Rule 5. */
  override fun onError(error: Throwable) = guarded {
    log("Quick Chat stream transport error: ${error.javaClass.name}")
    stop(CableStop.ERROR)
  }

  /**
   * Stops the client once: the first reason wins, a pending [confirmation] completes with `false`
   * and the socket, if any, is aborted. Safe from any thread; never throws.
   */
  fun stop(reason: CableStop) {
    val toAbort: CableSocket?
    synchronized(lock) {
      if (stopped != null) return
      stopped = reason
      frame.setLength(0)
      frame.trimToSize()
      toAbort = socket
      socket = null
    }
    confirmation.complete(false)
    log("Quick Chat stream stopped: $reason")
    try {
      toAbort?.abort()
    } catch (
      @Suppress("TooGenericExceptionCaught") // The socket is foreign code; stop must never throw.
      e: Exception,
    ) {
      log("Quick Chat stream abort failed: ${e.javaClass.name}")
    }
  }

  /** Rule 3: dispatch one complete ActionCable frame. */
  private fun handle(frame: JsonObject) {
    val ownIdentifier = frame.stringOrNull("identifier") == identifier
    when (frame.stringOrNull("type")) {
      "welcome" -> subscribe()
      "ping" -> Unit
      "confirm_subscription" ->
        if (ownIdentifier && synchronized(lock) { stopped == null }) confirmation.complete(true)
      "reject_subscription" -> if (ownIdentifier) stop(CableStop.REJECTED)
      "disconnect" -> stop(CableStop.DISCONNECTED)
      null -> {
        val message = frame["message"]
        if (ownIdentifier && message is JsonObject) deliver(message)
      }
      else -> Unit
    }
  }

  private fun subscribe() {
    val target = synchronized(lock) {
      if (stopped != null || subscribeSent || socket == null) return
      subscribeSent = true
      socket
    }
    val command = JsonObject().apply {
      addProperty("command", "subscribe")
      addProperty("identifier", identifier)
    }
    target?.sendText(GSON.toJson(command))
  }

  /**
   * Rule 4: [onMessage] runs outside the lock; its failure stops the stream. A [stop] racing with
   * this check may still let one message through, which the consumer must tolerate.
   */
  private fun deliver(message: JsonObject) {
    if (synchronized(lock) { stopped != null }) return
    try {
      onMessage(message)
    } catch (
      @Suppress("TooGenericExceptionCaught") // The consumer is foreign code; callbacks never throw.
      e: Exception,
    ) {
      log("Quick Chat stream listener failed: ${e.javaClass.name}")
      stop(CableStop.LISTENER_FAILED)
    }
  }

  /** Rule 6: nothing escapes a transport callback; an unexpected failure stops the stream. */
  private inline fun guarded(block: () -> Unit) {
    try {
      block()
    } catch (
      @Suppress("TooGenericExceptionCaught") // Callbacks run on transport threads and must never throw.
      e: Exception,
    ) {
      log("Quick Chat stream callback failed: ${e.javaClass.name}")
      stop(CableStop.ERROR)
    }
  }

  companion object {
    private val GSON = GsonBuilder().disableHtmlEscaping().create()

    /** Rule 7: the `GraphqlChannel` identifier, keys in this order (§11.1, K-S11). */
    fun identifier(query: String, variables: String): String =
      GSON.toJson(
        JsonObject().apply {
          addProperty("channel", "GraphqlChannel")
          addProperty("query", query)
          addProperty("variables", variables)
          addProperty("operationName", "aiCompletionResponse")
        },
      )

    /** Malformed frames are dropped (they were already counted). */
    private fun parse(text: String): JsonObject? =
      try {
        JsonParser.parseString(text) as? JsonObject
      } catch (
        @Suppress("SwallowedException") // A malformed frame is dropped; its text must not be logged.
        e: JsonParseException,
      ) {
        null
      }

    private fun JsonObject.stringOrNull(key: String): String? =
      get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun log(text: String) {
      runCatching { logger<ActionCableClient>().info(text) }
    }
  }
}

/**
 * Everything one connection received, dropped frames included (§15.3). Text parts add chars, binary
 * parts add bytes as-is; a frame is counted on its last part. Not thread-safe: the owner's lock
 * guards it.
 */
private class ReceiveCounter(private val maxChars: Long, private val maxFrames: Int) {
  private var chars = 0L
  private var frames = 0

  /** Adds one received part; `true` when a receive limit is exceeded. */
  fun add(size: Int, last: Boolean): Boolean {
    chars += size
    if (last) frames++
    return chars > maxChars || frames > maxFrames
  }
}
