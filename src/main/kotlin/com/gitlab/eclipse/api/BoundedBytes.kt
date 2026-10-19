package com.gitlab.eclipse.api

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** A response body read under a size cap: the whole body, or the fact that it exceeded the cap. */
sealed interface BoundedBytes {
  /** The complete body; at most the cap. */
  class Ok(val bytes: ByteArray) : BoundedBytes

  /** The body was larger than the cap; nothing beyond the cap was kept. */
  object TooLarge : BoundedBytes
}

/** Arms a one-shot timer; [onExpiry] runs on some other thread once [timeout] has passed. */
fun interface BodyDeadline {
  fun arm(timeout: Duration, onExpiry: () -> Unit)
}

/** Production [BodyDeadline]: the JDK's shared delayed executor (one daemon thread, no pool of ours). */
internal val SHARED_BODY_DEADLINE = BodyDeadline { timeout, onExpiry ->
  CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(onExpiry)
}

/** How long a body may take once its headers arrived; the request timeout covers only the headers. */
private const val DEFAULT_BODY_TIMEOUT_SECONDS = 30L
private val DEFAULT_BODY_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_BODY_TIMEOUT_SECONDS)

/** Inclusive bounds of the HTTP 2xx status class. */
private const val SUCCESS_MIN = 200
private const val SUCCESS_MAX = 299

/**
 * Reads a 2xx body into memory up to [maxBytes] (REVIEW-01 design §5.1, F4).
 *
 * - non-2xx → the body is discarded (the connection is released) and the result is `null`; the
 *   caller reports the status only, so an error page is never held or logged;
 * - a `Content-Length` over the cap → [BoundedBytes.TooLarge] without requesting any body;
 * - otherwise the body is accumulated and, the moment it passes the cap (chunked responses carry
 *   no length), the subscription is cancelled and the result is [BoundedBytes.TooLarge].
 */
internal class BoundedBytesHandler(
  private val maxBytes: Int,
  internal val bodyTimeout: Duration = DEFAULT_BODY_TIMEOUT,
  private val deadline: BodyDeadline = SHARED_BODY_DEADLINE,
) : HttpResponse.BodyHandler<BoundedBytes?> {
  override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<BoundedBytes?> {
    if (responseInfo.statusCode() !in SUCCESS_MIN..SUCCESS_MAX) {
      return HttpResponse.BodySubscribers.replacing(null)
    }
    val declared = responseInfo.headers().firstValueAsLong("Content-Length")
    if (declared.isPresent && declared.asLong > maxBytes) return RejectingSubscriber()
    return LimitingSubscriber(maxBytes, bodyTimeout, deadline)
  }
}

/** Cancels at once and answers [BoundedBytes.TooLarge]: the declared length already exceeds the cap. */
private class RejectingSubscriber : HttpResponse.BodySubscriber<BoundedBytes?> {
  private val result = CompletableFuture<BoundedBytes?>()

  override fun getBody(): CompletionStage<BoundedBytes?> = result

  override fun onSubscribe(subscription: Flow.Subscription) {
    subscription.cancel()
    result.complete(BoundedBytes.TooLarge)
  }

  override fun onNext(item: List<ByteBuffer>) = Unit

  override fun onError(throwable: Throwable) {
    result.complete(BoundedBytes.TooLarge)
  }

  override fun onComplete() {
    result.complete(BoundedBytes.TooLarge)
  }
}

/**
 * Accumulates the body until it completes, passes [maxBytes], or outlives [bodyTimeout]. The HTTP
 * client calls it serially; the deadline fires on another thread, which is why [subscription] is
 * volatile and every completion goes through the (thread-safe, first-wins) [result] future. On
 * expiry the subscription is cancelled and the body fails with [HttpTimeoutException], which
 * `HttpClient.send` rethrows as such — a stalled or trickling body cannot block the caller forever.
 */
private class LimitingSubscriber(
  private val maxBytes: Int,
  private val bodyTimeout: Duration,
  private val deadline: BodyDeadline,
) : HttpResponse.BodySubscriber<BoundedBytes?> {
  private val result = CompletableFuture<BoundedBytes?>()
  private val buffer = ByteArrayOutputStream()

  @Volatile
  private var subscription: Flow.Subscription? = null
  private var total = 0L

  override fun getBody(): CompletionStage<BoundedBytes?> = result

  override fun onSubscribe(subscription: Flow.Subscription) {
    this.subscription = subscription
    deadline.arm(bodyTimeout) {
      if (result.completeExceptionally(HttpTimeoutException(BODY_TIMED_OUT))) subscription.cancel()
    }
    subscription.request(Long.MAX_VALUE)
  }

  override fun onNext(item: List<ByteBuffer>) {
    if (result.isDone) return
    for (chunk in item) {
      total += chunk.remaining()
      if (total > maxBytes) {
        subscription?.cancel()
        result.complete(BoundedBytes.TooLarge)
        return
      }
      val bytes = ByteArray(chunk.remaining())
      chunk.get(bytes)
      buffer.write(bytes)
    }
  }

  override fun onError(throwable: Throwable) {
    result.completeExceptionally(throwable)
  }

  override fun onComplete() {
    if (!result.isDone) result.complete(BoundedBytes.Ok(buffer.toByteArray()))
  }

  private companion object {
    const val BODY_TIMED_OUT = "Response body not received in time"
  }
}
