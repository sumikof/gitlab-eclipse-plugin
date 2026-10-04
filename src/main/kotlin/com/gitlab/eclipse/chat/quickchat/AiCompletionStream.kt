package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.utils.logger
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toJavaDuration

/** Why [AiCompletionStreamOpener.open] gave no stream (design `quick-chat-streaming` §14). */
enum class StreamOpenFailure { NO_USER, USER_QUERY_FAILED, INVALID_ENDPOINT, CONNECT_FAILED, REJECTED, STOPPED, TIMED_OUT }

/** The outcome of [AiCompletionStreamOpener.open]. [Failed.exceptionClass] is a class name only, never a message. */
sealed interface StreamOpenResult {
  data class Opened(val stream: AiCompletionStream) : StreamOpenResult

  data class Failed(val reason: StreamOpenFailure, val exceptionClass: String? = null) : StreamOpenResult
}

/**
 * Opens the per-send answer stream (design `quick-chat-streaming` §9.1 s1, §14, §15.1): the user id,
 * then an ActionCable subscription to `aiCompletionResponse`, all within one wait limit.
 *
 * Only failure kinds and exception class names are logged — never the token, user id, identifier,
 * URL or any frame text (§19).
 */
class AiCompletionStreamOpener(
  private val api: QuickChatApi,
  private val sockets: CableSocketFactory = JdkCableSocketFactory(),
  private val clock: MonotonicClock = MonotonicClock.SYSTEM,
) {
  /**
   * §9.1 s1. Waits at most [waitLimit] for `confirm_subscription`. Never throws except
   * [CancellationException]; on every non-[StreamOpenResult.Opened] path, and on cancellation, the
   * client is stopped so its socket is aborted, now or when it opens later.
   */
  suspend fun open(
    connection: ConnectionSnapshot,
    clientSubscriptionId: String,
    waitLimit: Duration,
    onProgress: () -> Unit,
  ): StreamOpenResult {
    if (!waitLimit.isPositive()) return failed(StreamOpenFailure.TIMED_OUT)
    val deadline = clock.nanoTime() + waitLimit.inWholeNanoseconds
    val userId = when (val user = queryUser(connection, remaining(deadline))) {
      is UserQuery.Failed -> return user.result
      is UserQuery.Found -> user.id
    }
    if (!remaining(deadline).isPositive()) return failed(StreamOpenFailure.TIMED_OUT)
    val endpoint = try {
      CableEndpoint.of(connection.instanceUrl)
    } catch (
      @Suppress("SwallowedException") // Its message is about the instance URL, which must not be logged.
      e: IllegalArgumentException,
    ) {
      return failed(StreamOpenFailure.INVALID_ENDPOINT)
    }
    val identifier = ActionCableClient.identifier(SUBSCRIPTION_QUERY, variables(userId, clientSubscriptionId))
    val stream = AiCompletionStream(identifier, onProgress)
    val future = try {
      sockets.connect(endpoint, connection.token, stream.client)
    } catch (
      @Suppress("TooGenericExceptionCaught") // The factory is foreign code; open never throws.
      e: Exception,
    ) {
      stream.close()
      return failed(StreamOpenFailure.CONNECT_FAILED, e)
    }
    return awaitConfirmation(stream, future, remaining(deadline))
  }

  /** Step 2: the user id, or the failure that ends the open. */
  private suspend fun queryUser(connection: ConnectionSnapshot, timeout: Duration): UserQuery {
    val id = try {
      runInterruptible { api.currentUserId(connection, timeout.toJavaDuration()) }
    } catch (e: CancellationException) {
      throw e
    } catch (
      @Suppress("TooGenericExceptionCaught") // Any transport failure only means "no stream".
      e: Exception,
    ) {
      return UserQuery.Failed(failed(StreamOpenFailure.USER_QUERY_FAILED, e))
    }
    return if (id.isNullOrEmpty()) UserQuery.Failed(failed(StreamOpenFailure.NO_USER)) else UserQuery.Found(id)
  }

  /**
   * Step 6–7: the handshake and `confirm_subscription`. On every other path the stream is closed and
   * the handshake [future] cancelled, even when the wait was already over before it started.
   */
  private suspend fun awaitConfirmation(
    stream: AiCompletionStream,
    future: CompletableFuture<CableSocket>,
    timeout: Duration,
  ): StreamOpenResult {
    val confirmed = try {
      withTimeoutOrNull(timeout) {
        future.await()
        stream.client.confirmation.await()
      }
    } catch (e: CancellationException) {
      giveUp(stream, future)
      throw e
    } catch (
      @Suppress("TooGenericExceptionCaught") // A failed handshake (401/403/TLS/proxy) only means "no stream".
      e: Exception,
    ) {
      giveUp(stream, future)
      return failed(StreamOpenFailure.CONNECT_FAILED, unwrap(e))
    }
    if (confirmed == true) return StreamOpenResult.Opened(stream)
    val reason = when {
      confirmed == null -> StreamOpenFailure.TIMED_OUT
      stream.client.stopReason == CableStop.REJECTED -> StreamOpenFailure.REJECTED
      else -> StreamOpenFailure.STOPPED
    }
    giveUp(stream, future)
    return failed(reason)
  }

  /** Stops the client and cancels a handshake still in flight; never throws. */
  private fun giveUp(stream: AiCompletionStream, future: CompletableFuture<CableSocket>) {
    stream.close()
    try {
      future.cancel(true)
    } catch (
      @Suppress("TooGenericExceptionCaught") // The future may be a foreign subclass; giving up must not throw.
      e: Exception,
    ) {
      log("Quick Chat stream handshake cancel failed: ${e.javaClass.name}")
    }
  }

  private fun remaining(deadline: Long): Duration = (deadline - clock.nanoTime()).nanoseconds

  private sealed interface UserQuery {
    data class Found(val id: String) : UserQuery

    data class Failed(val result: StreamOpenResult.Failed) : UserQuery
  }

  companion object {
    private val GSON = GsonBuilder().disableHtmlEscaping().create()

    /** Streaming design §11.2 (REF `ai_completion_response_channel.ts:19-43`, `htmlResponse` form). */
    const val SUBSCRIPTION_QUERY =
      "subscription aiCompletionResponse(\$userId: UserID, \$clientSubscriptionId: String, \$aiAction: AiAction, " +
        "\$htmlResponse: Boolean = true) { aiCompletionResponse(userId: \$userId, aiAction: \$aiAction, " +
        "clientSubscriptionId: \$clientSubscriptionId) { id requestId content contentHtml @include(if: \$htmlResponse) " +
        "errors role timestamp type chunkId extras { sources } } }"

    /** K-S11: the subscription variables as a JSON string, keys in this order. */
    private fun variables(userId: String, clientSubscriptionId: String): String =
      GSON.toJson(
        JsonObject().apply {
          addProperty("htmlResponse", false)
          addProperty("userId", userId)
          addProperty("aiAction", "CHAT")
          addProperty("clientSubscriptionId", clientSubscriptionId)
        },
      )

    private fun unwrap(e: Throwable): Throwable =
      (e as? CompletionException ?: e as? ExecutionException)?.cause ?: e

    private fun failed(reason: StreamOpenFailure, cause: Throwable? = null): StreamOpenResult.Failed {
      val exceptionClass = cause?.javaClass?.name
      log("Quick Chat stream not opened: $reason" + exceptionClass?.let { " ($it)" }.orEmpty())
      return StreamOpenResult.Failed(reason, exceptionClass)
    }

    private fun log(text: String) {
      runCatching { logger<AiCompletionStreamOpener>().info(text) }
    }
  }
}

/**
 * One send's answer stream (design `quick-chat-streaming` §9.2, FR-S3): display only — it exposes
 * the text to show while the answer streams and never a result.
 *
 * [onProgress] is called, always outside this stream's lock, whenever the display text may have
 * changed. It has two callers that are not serialized with each other: the transport thread
 * delivering channel messages, and whichever thread calls [confirmRequestId]. Two invocations may
 * therefore overlap, and one may still run after [close] when it races a concurrent stop. Consumers
 * must tolerate concurrent and late calls — e.g. coalesce and re-read [displayText] (PR-2's
 * `ResultSink.progress` keeps only the latest).
 */
class AiCompletionStream internal constructor(
  identifier: String,
  private val onProgress: () -> Unit,
) : AutoCloseable {
  private val lock = Any()

  // Guarded by [lock].
  private val assembler = ChunkAssembler()

  internal val client = ActionCableClient(identifier, onMessage = ::onChannelMessage)

  /** Why the stream stopped; null while live. */
  val stopReason: CableStop?
    get() = client.stopReason

  val chunksAccepted: Int
    get() = synchronized(lock) { assembler.chunksAccepted }

  val seriesResets: Int
    get() = synchronized(lock) { assembler.seriesResets }

  val finalReceived: Boolean
    get() = synchronized(lock) { assembler.finalReceived }

  fun displayText(): String = synchronized(lock) { assembler.displayText() }

  /**
   * Fixes the send's requestId; held chunks of it become visible. May call [onProgress] on the
   * calling thread, concurrently with a transport-thread call (see the class KDoc); an [onProgress]
   * failure propagates to the caller here.
   */
  fun confirmRequestId(requestId: String) {
    val change = synchronized(lock) { assembler.confirmRequestId(requestId) }
    react(change)
  }

  /** Idempotent and non-blocking: stops the client, which aborts the socket. */
  override fun close() {
    client.stop(CableStop.CLOSED_BY_CLIENT)
  }

  /**
   * One `GraphqlChannel` message. Shapes other than `result.data.aiCompletionResponse` are dropped.
   * A message delivered after a concurrent stop (ActionCableClient rule 4) is ignored. An
   * [onProgress] failure propagates so the client stops with `LISTENER_FAILED`.
   */
  internal fun onChannelMessage(message: JsonObject) {
    if (client.stopReason != null) return
    val frame = parseFrame(message) ?: return
    val change = synchronized(lock) { assembler.accept(frame) }
    react(change)
  }

  private fun react(change: ChunkAssembler.Change) {
    if (client.stopReason != null) return
    when (change) {
      ChunkAssembler.Change.DISPLAY -> onProgress()
      ChunkAssembler.Change.OVERFLOW -> client.stop(CableStop.OVERFLOW)
      ChunkAssembler.Change.NONE -> Unit
    }
  }

  private companion object {
    private val GSON = GsonBuilder().create()

    fun parseFrame(message: JsonObject): StreamFrame? {
      val response = message.objectOrNull("result")?.objectOrNull("data")?.objectOrNull("aiCompletionResponse")
        ?: return null
      val dto = try {
        GSON.fromJson(response, StreamFrameDto::class.java)
      } catch (
        @Suppress("SwallowedException") // A malformed frame is dropped; its text must not be logged.
        e: JsonParseException,
      ) {
        null
      } ?: return null
      return StreamFrame(dto.requestId, dto.role, dto.content, dto.errors?.filterNotNull(), dto.chunkId)
    }

    fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject
  }
}

// Raw Gson parse target; every field nullable (see QuickChatApi.kt).
internal data class StreamFrameDto(
  val requestId: String?,
  val role: String?,
  val content: String?,
  val errors: List<String?>?,
  val chunkId: Int?,
)
