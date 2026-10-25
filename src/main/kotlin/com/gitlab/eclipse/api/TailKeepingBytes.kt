package com.gitlab.eclipse.api

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** The result of a conditional trace GET. */
sealed interface TraceFetch {
  /** The server answered 304: the caller's copy is current. */
  data object NotModified : TraceFetch

  /**
   * The last [bytes] of the response body. When the body was longer than the cap, the start was moved to just after
   * a line break (or, without one, to a UTF-8 character boundary). [totalBytes] is the size of the whole body and
   * [omittedBytes] = [totalBytes] - [bytes].size.
   */
  class Body(val bytes: ByteArray, val etag: String?, val totalBytes: Long, val omittedBytes: Long) : TraceFetch
}

/** What [TailKeepingBodyHandler] produces: a finished fetch, or the status of a rejected response. */
internal sealed interface TailOutcome {
  class Done(val fetch: TraceFetch) : TailOutcome

  /** A non-2xx, non-304 status; the body was never read. */
  class Rejected(val status: Int, val correlationId: String?) : TailOutcome
}

private const val DEFAULT_BODY_TIMEOUT_SECONDS = 30L
private val REARM_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos()
private const val NOT_MODIFIED = 304
private const val SUCCESS_MIN = 200
private const val SUCCESS_MAX = 299
private const val LF: Byte = 0x0A

/** How far into the kept tail a line break is looked for; beyond it the first line is considered too long to drop. */
private const val TAIL_LF_SEARCH_BYTES = 1 * 1024 * 1024
private const val CONTINUATION_MASK = 0xC0
private const val CONTINUATION_BITS = 0x80

/**
 * Reads the end of a 2xx body: only the last [maxBytes] are kept (chunks older than that are dropped as they
 * arrive), so memory stays bounded however large the trace is.
 *
 * - 304 → [TraceFetch.NotModified], other non-2xx → [TailOutcome.Rejected]; neither reads any body;
 * - a body that stays silent for [bodyTimeout] fails with [HttpTimeoutException]. It is an idle deadline: every received
 *   chunk pushes it back (re-armed at most once a second), so a slow but steady download of a large trace survives.
 */
internal class TailKeepingBodyHandler(
  private val maxBytes: Int,
  internal val bodyTimeout: Duration = Duration.ofSeconds(DEFAULT_BODY_TIMEOUT_SECONDS),
  private val deadline: BodyDeadline = SharedBodyDeadline,
  private val clock: () -> Long = System::nanoTime,
) : HttpResponse.BodyHandler<TailOutcome> {
  override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<TailOutcome> {
    val status = responseInfo.statusCode()
    return when {
      status == NOT_MODIFIED -> ImmediateSubscriber(TailOutcome.Done(TraceFetch.NotModified))
      status !in SUCCESS_MIN..SUCCESS_MAX -> ImmediateSubscriber(
        TailOutcome.Rejected(status, responseInfo.headers().firstValue("x-request-id").orElse(null)),
      )
      else -> TailSubscriber(
        maxBytes,
        responseInfo.headers().firstValue("ETag").orElse(null),
        bodyTimeout,
        deadline,
        clock,
      )
    }
  }
}

/** Cancels at once, reading nothing, and answers [answer]. */
private class ImmediateSubscriber(private val answer: TailOutcome) : HttpResponse.BodySubscriber<TailOutcome> {
  private val result = CompletableFuture<TailOutcome>()

  override fun getBody(): CompletionStage<TailOutcome> = result

  override fun onSubscribe(subscription: Flow.Subscription) {
    subscription.cancel()
    result.complete(answer)
  }

  override fun onNext(item: List<ByteBuffer>) = Unit

  override fun onError(throwable: Throwable) {
    result.complete(answer)
  }

  override fun onComplete() {
    result.complete(answer)
  }
}

/** Keeps the newest [maxBytes] as a queue of chunks; [headSkip] bytes of the first chunk are already dropped. */
private class TailSubscriber(
  private val maxBytes: Int,
  private val etag: String?,
  private val bodyTimeout: Duration,
  private val deadline: BodyDeadline,
  private val clock: () -> Long,
) : HttpResponse.BodySubscriber<TailOutcome> {
  private val result = CompletableFuture<TailOutcome>()
  private val chunks = ArrayDeque<ByteArray>()
  private var headSkip = 0
  private var kept = 0L
  private var total = 0L
  private var lastArmedAt = 0L

  @Volatile
  private var subscription: Flow.Subscription? = null

  @Volatile
  private var disarm: () -> Unit = {}

  override fun getBody(): CompletionStage<TailOutcome> = result

  override fun onSubscribe(subscription: Flow.Subscription) {
    this.subscription = subscription
    armDeadline(subscription)
    subscription.request(Long.MAX_VALUE)
  }

  /** The expiry action captures only the future and the subscription, never this subscriber or its chunks. */
  private fun armDeadline(subscription: Flow.Subscription) {
    val body = result
    lastArmedAt = clock()
    disarm = deadline.arm(bodyTimeout) {
      if (body.completeExceptionally(HttpTimeoutException(BODY_TIMED_OUT))) subscription.cancel()
    }
  }

  /** Pushes the idle deadline back, but at most once per [REARM_INTERVAL_NANOS]. */
  private fun extendDeadline() {
    val current = subscription ?: return
    if (clock() - lastArmedAt < REARM_INTERVAL_NANOS) return
    disarm()
    armDeadline(current)
  }

  override fun onNext(item: List<ByteBuffer>) {
    if (result.isDone) return
    extendDeadline()
    for (buffer in item) {
      val bytes = ByteArray(buffer.remaining())
      buffer.get(bytes)
      chunks.addLast(bytes)
      total += bytes.size
      kept += bytes.size
      dropOldest()
    }
  }

  private fun dropOldest() {
    while (kept > maxBytes) {
      val excess = (kept - maxBytes).toInt()
      val available = chunks.first().size - headSkip
      if (excess >= available) {
        chunks.removeFirst()
        headSkip = 0
        kept -= available
      } else {
        headSkip += excess
        kept -= excess
      }
    }
  }

  override fun onError(throwable: Throwable) {
    finish { result.completeExceptionally(throwable) }
  }

  override fun onComplete() {
    val tail = collect()
    val bytes = if (total > maxBytes) alignToBoundary(tail) else tail
    val fetch = TraceFetch.Body(bytes, etag, total, total - bytes.size)
    finish { result.complete(TailOutcome.Done(fetch)) }
  }

  private fun collect(): ByteArray {
    val out = ByteArrayOutputStream(kept.toInt())
    chunks.forEachIndexed { i, chunk ->
      val from = if (i == 0) headSkip else 0
      out.write(chunk, from, chunk.size - from)
    }
    return out.toByteArray()
  }

  /** Completes [result] (first completion wins), stops the deadline and drops the chunks. */
  private inline fun finish(complete: () -> Unit) {
    complete()
    disarm()
    chunks.clear()
  }

  private companion object {
    const val BODY_TIMED_OUT = "Response body not received in time"
  }
}

/**
 * Starts just after the first line break, provided that break lies within the first [TAIL_LF_SEARCH_BYTES] of the
 * tail and is not its last byte (a break at the very end would leave nothing to show). Otherwise a giant line is cut
 * where it falls: leading UTF-8 continuation bytes are skipped so the text starts on a character boundary.
 */
private fun alignToBoundary(tail: ByteArray): ByteArray {
  val searchEnd = minOf(tail.size, TAIL_LF_SEARCH_BYTES)
  var lf = -1
  for (i in 0 until searchEnd) {
    if (tail[i] == LF) {
      lf = i
      break
    }
  }
  if (lf in 0 until tail.size - 1) return tail.copyOfRange(lf + 1, tail.size)
  var start = 0
  while (start < tail.size && (tail[start].toInt() and CONTINUATION_MASK) == CONTINUATION_BITS) start++
  return tail.copyOfRange(start, tail.size)
}
